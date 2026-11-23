"""The assistant over HTTP (ADR-0093), against its contract api-specs/traveller-assistant-api.yaml."""

import threading
from pathlib import Path

import pytest
import yaml
from prometheus_client import CollectorRegistry
from starlette.testclient import TestClient

from traveller_assistant import assistant as rules
from traveller_assistant.assistant import Answer, ModelUnavailable, Route, Source
from traveller_assistant.web import MIN_TOKEN_LENGTH, create_app, mcp_server_parameters

TOKEN = "t" * 40
AUTH = {"Authorization": f"Bearer {TOKEN}"}
SPEC = yaml.safe_load((Path(__file__).resolve().parents[3] / "api-specs" / "traveller-assistant-api.yaml")
                      .read_text(encoding="utf-8"))
SCHEMAS = SPEC["components"]["schemas"]
ANSWERED = Answer("Check-in opens 24 hours before departure.", Route.HELP, "answered",
                  (Source("checking-in-online", "Checking in online"),))


class FakeAssistant:
    def __init__(self, result=ANSWERED, hold: threading.Event | None = None):
        self.result = result
        self.hold = hold
        self.started = threading.Event()
        self.seen = []

    def answer(self, conversation):
        self.seen.append(conversation)
        self.started.set()
        if self.hold:
            self.hold.wait(5)
        if isinstance(self.result, BaseException):
            raise self.result
        return self.result


def client(assistant=None, **options):
    app = create_app(assistant or FakeAssistant(), TOKEN, registry=CollectorRegistry(), **options)
    return TestClient(app, raise_server_exceptions=False)


def post(http, body, headers=AUTH):
    return http.post("/api/v1/assistant/messages", json=body, headers=headers)


# Who may call it


@pytest.mark.parametrize("headers", [{}, {"Authorization": "Bearer wrong"}, {"Authorization": TOKEN},
                                     {"Authorization": f"Bearer {TOKEN}x"}])
def test_only_the_website_with_the_shared_token_may_ask(headers):
    assistant = FakeAssistant()

    response = post(client(assistant), {"message": "Hi"}, headers)

    assert response.status_code == 401 and assistant.seen == []


def test_refuses_to_start_with_a_short_token():
    with pytest.raises(ValueError, match=str(MIN_TOKEN_LENGTH)):
        create_app(FakeAssistant(), "short", registry=CollectorRegistry())


# Answers


def test_answers_with_the_reply_its_route_outcome_and_sources_and_is_never_cached():
    assistant = FakeAssistant()

    response = post(client(assistant), {"message": "When can I check in?",
                                        "history": [{"role": "traveller", "text": "Hello"}]})

    assert response.status_code == 200
    assert response.json() == {"reply": "Check-in opens 24 hours before departure.", "route": "help",
                               "outcome": "answered",
                               "sources": [{"article": "checking-in-online", "title": "Checking in online"}]}
    assert response.headers["cache-control"] == "no-store"
    assert assistant.seen[0].message == "When can I check in?" and assistant.seen[0].history[0].text == "Hello"


@pytest.mark.parametrize("body", [{"message": ""}, {"message": "x" * 1001}, {"nope": 1},
                                  {"message": "hi", "history": [{"role": "admin", "text": "x"}]}])
def test_a_message_it_cannot_take_is_a_400_that_says_why(body):
    assistant = FakeAssistant()

    response = post(client(assistant), body)

    assert response.status_code == 400 and response.json()["detail"] and assistant.seen == []


def test_a_body_that_is_not_json_is_a_400():
    response = client().post("/api/v1/assistant/messages", content=b"{not json", headers=AUTH)

    assert response.status_code == 400


def test_a_model_that_cannot_answer_is_a_503_with_retry_after_worded_for_the_traveller():
    response = post(client(FakeAssistant(ModelUnavailable("RateLimitError"))), {"message": "Hi"})

    assert response.status_code == 503 and response.headers["retry-after"] == "30"
    assert "try again" in response.json()["detail"] and "RateLimitError" not in response.json()["detail"]


def test_an_unexpected_failure_is_a_500_that_gives_nothing_away():
    response = post(client(FakeAssistant(RuntimeError("secret internals at /srv/app.py"))), {"message": "Hi"})

    assert response.status_code == 500 and "secret" not in response.text


def test_at_most_the_allowed_number_of_answers_run_at_once_and_the_next_caller_is_turned_away():
    release = threading.Event()
    busy = FakeAssistant(hold=release)
    http = client(busy, max_concurrent=1, wait_seconds=0.05)
    first = threading.Thread(target=post, args=(http, {"message": "first"}))
    first.start()
    try:
        assert busy.started.wait(5)
        second = post(http, {"message": "second"})
    finally:
        release.set()
        first.join(5)

    assert second.status_code == 503 and "busy" in second.json()["detail"]
    assert [c.message for c in busy.seen] == ["first"]


# Starting the MCP server


def test_the_mcp_server_is_started_without_the_model_key_or_the_api_token_because_it_holds_no_credentials():
    parameters = mcp_server_parameters({"GROQ_API_KEY": "gsk_secret", "SKYHUB_ASSISTANT_API_TOKEN": TOKEN,
                                        "SKYHUB_SEARCH_API_URL": "http://flight-search:8081", "PATH": "/usr/bin"})

    assert parameters.args == ["-m", "traveller_assistant.server"]
    assert parameters.env["SKYHUB_SEARCH_API_URL"] == "http://flight-search:8081"
    assert "GROQ_API_KEY" not in parameters.env and "SKYHUB_ASSISTANT_API_TOKEN" not in parameters.env
    assert "gsk_secret" not in str(parameters.env) and TOKEN not in str(parameters.env)


# Health and metrics


def test_says_it_is_up():
    assert client().get("/healthz").json() == {"status": "up"}


def test_counts_every_message_by_route_and_outcome_and_times_them():
    http = client()
    post(http, {"message": "When can I check in?"})
    post(http, {"message": ""})

    metrics = http.get("/metrics").text
    assert 'skyhub_assistant_messages_total{outcome="answered",route="help"} 1.0' in metrics
    assert 'skyhub_assistant_messages_total{outcome="invalid",route="none"} 1.0' in metrics
    assert "skyhub_assistant_message_seconds_count" in metrics


def test_counts_a_model_outage_so_an_alert_can_see_it():
    http = client(FakeAssistant(ModelUnavailable("APIConnectionError")))
    post(http, {"message": "Hi"})

    assert 'skyhub_assistant_messages_total{outcome="unavailable",route="none"} 1.0' in http.get("/metrics").text


# The contract


def test_every_answer_has_what_the_contract_requires_with_values_it_allows():
    body = post(client(), {"message": "When can I check in?"}).json()

    assert set(SCHEMAS["Answer"]["required"]) <= set(body)
    assert body["route"] in SCHEMAS["Answer"]["properties"]["route"]["enum"]
    assert body["outcome"] in SCHEMAS["Answer"]["properties"]["outcome"]["enum"]
    assert all(set(SCHEMAS["Source"]["required"]) <= set(s) for s in body["sources"])


def test_the_contract_and_the_code_agree_on_every_limit():
    request, turn = SCHEMAS["MessageRequest"]["properties"], SCHEMAS["Turn"]["properties"]

    assert request["message"]["maxLength"] == rules.MAX_MESSAGE
    assert request["history"]["maxItems"] == rules.MAX_TURNS
    assert turn["text"]["maxLength"] == rules.MAX_TURN
    assert turn["role"]["enum"] == ["traveller", "assistant"]
    assert set(SCHEMAS["Answer"]["properties"]["route"]["enum"]) == {r.value for r in Route}
