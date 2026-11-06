import json
from types import SimpleNamespace

from disruption_agent.llm import GroqChatModel, ToolSpec

SPEC = ToolSpec(name="find_alternatives", description="Find flights", parameters={"type": "object", "properties": {}})


class FakeGroq:
    """Records what the adapter sends and answers like Groq's chat completions."""

    def __init__(self, message):
        self.sent = None
        self.chat = SimpleNamespace(completions=SimpleNamespace(create=self._create))
        self._message = message

    def _create(self, **kwargs):
        self.sent = kwargs
        return SimpleNamespace(choices=[SimpleNamespace(message=self._message)])


def test_sends_the_conversation_and_the_tools_in_the_chat_format_with_a_steady_temperature():
    fake = FakeGroq(SimpleNamespace(content="ok", tool_calls=None))

    GroqChatModel(fake, model="llama-3.3-70b-versatile").complete([{"role": "user", "content": "hi"}], [SPEC])

    assert fake.sent["model"] == "llama-3.3-70b-versatile"
    assert fake.sent["messages"] == [{"role": "user", "content": "hi"}]
    assert fake.sent["tools"] == [{"type": "function", "function": {
        "name": "find_alternatives", "description": "Find flights", "parameters": SPEC.parameters}}]
    assert fake.sent["temperature"] == 0


def test_turns_a_tool_request_into_a_parsed_call():
    message = SimpleNamespace(content=None, tool_calls=[SimpleNamespace(
        id="c1", function=SimpleNamespace(name="find_alternatives", arguments=json.dumps({"origin": "DXB"})))])

    turn = GroqChatModel(FakeGroq(message), model="m").complete([], [SPEC])

    assert turn.content is None
    assert [(c.id, c.name, c.arguments) for c in turn.tool_calls] == [("c1", "find_alternatives", {"origin": "DXB"})]


def test_survives_a_tool_call_whose_arguments_are_not_valid_json():
    message = SimpleNamespace(content=None, tool_calls=[SimpleNamespace(
        id="c1", function=SimpleNamespace(name="find_alternatives", arguments="{not json"))])

    turn = GroqChatModel(FakeGroq(message), model="m").complete([], [SPEC])

    assert turn.tool_calls[0].arguments == {"__invalid_json__": "{not json"}


def test_omits_the_tools_field_when_there_are_none():
    fake = FakeGroq(SimpleNamespace(content="done", tool_calls=None))

    GroqChatModel(fake, model="m").complete([], [])

    assert "tools" not in fake.sent


class GroqRefusal(Exception):
    """Shaped like groq.BadRequestError: a 400 whose text carries the code."""

    def __init__(self, code: str, status: int = 400):
        super().__init__(f"Error code: {status} - {{'error': {{'code': '{code}'}}}}")
        self.status_code = status


class FlakyGroq(FakeGroq):
    def __init__(self, failures, message):
        super().__init__(message)
        self.failures = list(failures)
        self.calls = 0

    def _create(self, **kwargs):
        self.calls += 1
        if self.failures:
            raise self.failures.pop(0)
        return super()._create(**kwargs)


def test_asks_again_when_groq_rejects_the_models_tool_call_as_malformed():
    fake = FlakyGroq([GroqRefusal("tool_use_failed")], SimpleNamespace(content="ok", tool_calls=None))

    turn = GroqChatModel(fake, model="m").complete([], [SPEC])

    assert (turn.content, fake.calls) == ("ok", 2)


def test_gives_no_answer_rather_than_crashing_when_the_model_keeps_sending_malformed_calls():
    fake = FlakyGroq([GroqRefusal("tool_use_failed")] * 3, SimpleNamespace(content="never", tool_calls=None))

    turn = GroqChatModel(fake, model="m").complete([], [SPEC])

    assert turn.tool_calls == [] and fake.calls == 3      # the agent hands the batch to a person


def test_still_raises_every_other_error_such_as_a_model_that_does_not_exist():
    fake = FlakyGroq([GroqRefusal("model_not_found", status=404)], SimpleNamespace(content="ok", tool_calls=None))

    try:
        GroqChatModel(fake, model="m").complete([], [SPEC])
    except GroqRefusal:
        return
    raise AssertionError("a 404 must not be swallowed")
