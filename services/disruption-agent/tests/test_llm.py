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
