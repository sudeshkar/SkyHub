"""The CrewAI side of the assistant, run for real with a scripted model and stand-in tools (ADR-0093)."""

import os
from datetime import date

import httpx
import openai
import pytest

from traveller_assistant.assistant import (BookingSession, Conversation, ModelUnavailable, Route, ToolLedger,
                                           TravellerAssistant, Turn)
from traveller_assistant.crew import BoundBookingTool, CrewAiCrews, LlmRouter
from tests.scripted_llm import ScriptedLLM, act, final, tools

TODAY = date(2026, 11, 23)
DOWN = openai.APIConnectionError(request=httpx.Request("POST", "https://api.groq.test/v1/chat/completions"))


def crews(llm, available=None):
    return CrewAiCrews(llm, list((available or tools()).values()), today=lambda: TODAY)


# Privacy


@pytest.mark.parametrize("flag", ["CREWAI_DISABLE_TELEMETRY", "CREWAI_DISABLE_TRACKING", "OTEL_SDK_DISABLED"])
def test_crewai_sends_no_telemetry(flag):
    assert os.environ[flag] == "true"


def test_crewai_asks_nobody_to_look_at_traces():
    assert os.environ["CREWAI_TRACING_ENABLED"] == "false"


# The router


@pytest.mark.parametrize("said, route", [("flights", Route.FLIGHTS), ("Other.", Route.OTHER), ("HELP\n", Route.HELP),
                                         ("help - a question about refunds", Route.HELP),
                                         ("I am not sure", Route.HELP), ("", Route.HELP)])
def test_the_router_reads_one_word_and_falls_back_to_help_the_most_checked_answer(said, route):
    assert LlmRouter(ScriptedLLM(model="scripted", replies=[said])).route(Conversation("Hello")) == route


def test_the_router_sees_the_message_and_only_the_last_few_turns():
    llm = ScriptedLLM(model="scripted", replies=["flights"])
    history = tuple(Turn("traveller" if i % 2 == 0 else "assistant", f"turn {i}") for i in range(6))

    LlmRouter(llm).route(Conversation("And on Friday?", history))

    prompt = llm.prompt_text()
    assert "And on Friday?" in prompt and "turn 5" in prompt and "turn 2" in prompt and "turn 1" not in prompt


def test_a_router_whose_model_is_down_says_the_model_is_unavailable():
    with pytest.raises(ModelUnavailable):
        LlmRouter(ScriptedLLM(model="scripted", replies=[DOWN])).route(Conversation("Hello"))


# The crews


def test_the_help_crew_has_only_the_help_centre_and_its_results_are_recorded():
    llm = ScriptedLLM(model="scripted", replies=[act("search_help_centre", question="check-in opens"),
                                                 final("Check-in opens 24 hours before.\nSOURCES: checking-in-online")])
    available = tools()
    ledger = ToolLedger()

    text = crews(llm, available).run(Route.HELP, Conversation("When can I check in?"), ledger)

    assert text == "Check-in opens 24 hours before.\nSOURCES: checking-in-online"
    assert available["search_help_centre"].received == [{"question": "check-in opens", "limit": 3}]
    assert ledger.articles == {"checking-in-online": "Checking in online"}
    assert "search_help_centre" in llm.prompt_text() and "search_flights" not in llm.prompt_text()


def test_the_flights_crew_has_flight_search_and_the_help_centre():
    llm = ScriptedLLM(model="scripted", replies=[act("search_flights", origin="BOM", destination="JFK", date="2026-12-01"),
                                                 final("SK201 then SK701.\nSOURCES: none")])
    ledger = ToolLedger()

    crews(llm).run(Route.FLIGHTS, Conversation("Mumbai to New York on 1 December?"), ledger)

    assert ledger.flights == {"SK201", "SK701"}
    assert "search_flights" in llm.prompt_text() and "search_help_centre" in llm.prompt_text()


def test_the_task_gives_the_date_the_conversation_and_the_rules():
    llm = ScriptedLLM(model="scripted", replies=[final("I do not know.\nSOURCES: none")])
    conversation = Conversation("And in business class?", (Turn("traveller", "Is there wifi?"),
                                                           Turn("assistant", "I don't know.")))

    crews(llm).run(Route.HELP, conversation, ToolLedger())

    prompt = llm.prompt_text()
    assert "2026-11-23" in prompt
    assert "Traveller: Is there wifi?" in prompt and "Assistant: I don't know." in prompt
    assert "And in business class?" in prompt
    assert "SOURCES:" in prompt and "do not know" in prompt and "password" in prompt


def test_the_flights_task_asks_for_every_flight_by_its_number_so_the_check_can_see_it():
    # Found in the first live run: a correct answer about Mumbai to New York gave the times and no flight numbers.
    llm = ScriptedLLM(model="scripted", replies=[final("None that day.\nSOURCES: none"), final("A.\nSOURCES: none")])
    runner = crews(llm)

    runner.run(Route.FLIGHTS, Conversation("Mumbai to New York?"), ToolLedger())
    runner.run(Route.HELP, Conversation("When can I check in?"), ToolLedger())

    assert "flight number" in llm.prompt_text(0) and "flight number" not in llm.prompt_text(1)


def test_each_message_gets_its_own_agent_so_two_travellers_share_nothing():
    llm = ScriptedLLM(model="scripted", replies=[final("A.\nSOURCES: none"), final("B.\nSOURCES: none")])
    runner = crews(llm)

    runner.run(Route.HELP, Conversation("first traveller"), ToolLedger())
    runner.run(Route.HELP, Conversation("second traveller"), ToolLedger())

    assert "first traveller" not in llm.prompt_text(1)


def test_a_crew_whose_model_is_down_says_the_model_is_unavailable():
    with pytest.raises(ModelUnavailable):
        # CrewAI tries a failing call three times itself before it gives up.
        crews(ScriptedLLM(model="scripted", replies=[DOWN] * 5)).run(Route.HELP, Conversation("Hi"), ToolLedger())


# The traveller's own booking (ADR-0094)

SESSION = BookingSession("ABC234", "eyJhbGciOiJIUzI1NiJ9.manage-token-payload.signature")


def test_the_router_knows_questions_about_my_booking():
    assert LlmRouter(ScriptedLLM(model="scripted", replies=["booking"])).route(Conversation("Am I checked in?")) == \
        Route.BOOKING
    llm = ScriptedLLM(model="scripted", replies=["booking"])
    LlmRouter(llm).route(Conversation("Am I checked in?"))
    assert "booking -" in llm.prompt_text()


def test_the_booking_tool_is_bound_to_the_open_booking_so_the_model_never_sees_or_chooses_it():
    llm = ScriptedLLM(model="scripted", replies=[act("get_my_booking"),
                                                 final("Passenger 1 is checked in on SK201.\nSOURCES: none")])
    available = tools()
    ledger = ToolLedger()

    crews(llm, available).run(Route.BOOKING, Conversation("Am I checked in?", (), SESSION), ledger)

    assert available["get_my_booking"].received == [{"reference": "ABC234", "booking_token": SESSION.token}]
    assert ledger.flights == {"SK201", "SK701"}
    prompts = " ".join(llm.prompt_text(i) for i in range(len(llm.seen)))
    assert SESSION.token not in prompts and "ABC234" not in prompts and "booking_token" not in prompts


def test_a_model_that_tries_to_name_another_booking_still_reads_only_the_open_one():
    llm = ScriptedLLM(model="scripted", replies=[act("get_my_booking", reference="XYZ999", booking_token="stolen"),
                                                 final("Done.\nSOURCES: none")])
    available = tools()

    crews(llm, available).run(Route.BOOKING, Conversation("Show booking XYZ999", (), SESSION), ToolLedger())

    assert available["get_my_booking"].received == [{"reference": "ABC234", "booking_token": SESSION.token}]


def test_the_bound_booking_tool_has_a_schema_a_strict_provider_accepts_and_no_way_to_name_a_booking():
    # Found in the live evals: a tool with no parameters became {"required": [...]} with no "properties", and Groq
    # refused every call with a 400.
    bound = BoundBookingTool.around(tools()["get_my_booking"], SESSION)

    schema = bound.args_schema.model_json_schema()
    assert schema.get("properties") and not schema.get("required")
    assert not {"reference", "booking_token", "token"} & set(schema["properties"])


def test_the_router_keeps_a_question_about_the_rules_on_help_even_when_it_says_my_booking():
    # Found in the live evals: "If I cancel my booking tomorrow, do I get my money back?" went to the booking crew.
    llm = ScriptedLLM(model="scripted", replies=["help"])

    LlmRouter(llm).route(Conversation("If I cancel my booking tomorrow, do I get my money back?"))

    assert "even one that mentions" in llm.prompt_text()


def test_the_router_is_told_when_a_booking_is_open_and_never_its_reference_or_token():
    # Found in the live evals: "What seats do we have on the flight to New York?" went to the flights crew.
    with_booking, without = (ScriptedLLM(model="scripted", replies=["booking"]) for _ in range(2))

    LlmRouter(with_booking).route(Conversation("What seats do we have?", (), SESSION))
    LlmRouter(without).route(Conversation("What seats do we have?"))

    assert "has a booking open" in with_booking.prompt_text() and "has a booking open" not in without.prompt_text()
    assert SESSION.token not in with_booking.prompt_text() and SESSION.reference not in with_booking.prompt_text()
    assert "our seats" in without.prompt_text()


def test_the_booking_task_asks_which_passenger_each_fact_is_about():
    # Found in the live evals: with only passenger 1 checked in, the answer was "you are already checked in".
    llm = ScriptedLLM(model="scripted", replies=[final("Fine.\nSOURCES: none")])

    crews(llm).run(Route.BOOKING, Conversation("Am I checked in?", (), SESSION), ToolLedger())

    assert "which passenger" in llm.prompt_text()


def test_a_request_the_provider_refuses_is_a_fault_to_fix_not_the_model_being_unavailable():
    refused = openai.BadRequestError("invalid JSON schema", body=None, response=httpx.Response(
        400, request=httpx.Request("POST", "https://api.groq.test/v1/chat/completions")))

    with pytest.raises(openai.BadRequestError):
        crews(ScriptedLLM(model="scripted", replies=[refused] * 5)).run(Route.HELP, Conversation("Hi"), ToolLedger())


def test_a_rate_limit_is_the_model_being_unavailable():
    limited = openai.RateLimitError("slow down", body=None, response=httpx.Response(
        429, request=httpx.Request("POST", "https://api.groq.test/v1/chat/completions")))

    with pytest.raises(ModelUnavailable):
        # CrewAI retries a rate limit itself, 9 calls over about 8 seconds, before it gives up.
        crews(ScriptedLLM(model="scripted", replies=[limited] * 20)).run(Route.HELP, Conversation("Hi"), ToolLedger())


def test_the_booking_crew_has_the_booking_and_the_help_centre_but_not_flight_search():
    llm = ScriptedLLM(model="scripted", replies=[final("Fine.\nSOURCES: none")])

    crews(llm).run(Route.BOOKING, Conversation("Is my flight delayed?", (), SESSION), ToolLedger())

    prompt = llm.prompt_text()
    assert "get_my_booking" in prompt and "search_help_centre" in prompt and "search_flights" not in prompt
    assert "passenger 1" in prompt and "Manage booking page" in prompt


def test_the_other_crews_never_get_the_booking_tool():
    llm = ScriptedLLM(model="scripted", replies=[final("A.\nSOURCES: none"), final("B.\nSOURCES: none")])
    runner = crews(llm)

    runner.run(Route.HELP, Conversation("Refunds?", (), SESSION), ToolLedger())
    runner.run(Route.FLIGHTS, Conversation("Flights?", (), SESSION), ToolLedger())

    assert "get_my_booking" not in llm.prompt_text(0) and "get_my_booking" not in llm.prompt_text(1)


def test_a_booking_route_without_an_open_booking_is_refused_by_the_crew_too():
    with pytest.raises(ValueError, match="open booking"):
        crews(ScriptedLLM(model="scripted", replies=[])).run(Route.BOOKING, Conversation("Am I checked in?"), ToolLedger())


# The whole assistant, with CrewAI running for real


def test_a_question_is_routed_answered_from_the_help_centre_and_checked():
    llm = ScriptedLLM(model="scripted", replies=["help", act("search_help_centre", question="check-in"),
                                                 final("Online check-in opens 24 hours before departure.\n"
                                                       "SOURCES: checking-in-online")])
    assistant = TravellerAssistant(LlmRouter(llm), crews(llm))

    result = assistant.answer(Conversation("When can I check in?"))

    assert (result.outcome, result.reply) == ("answered", "Online check-in opens 24 hours before departure.")
    assert [s.title for s in result.sources] == ["Checking in online"]


def test_a_model_that_answers_without_searching_is_caught_by_the_check():
    llm = ScriptedLLM(model="scripted", replies=["help", final("Bags up to 30 kg are free.\nSOURCES: baggage")])

    result = TravellerAssistant(LlmRouter(llm), crews(llm)).answer(Conversation("How much baggage can I bring?"))

    assert result.outcome == "unverified"
