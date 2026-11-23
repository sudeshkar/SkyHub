"""The CrewAI side of the assistant, run for real with a scripted model and stand-in tools (ADR-0093)."""

import os
from datetime import date

import httpx
import openai
import pytest

from traveller_assistant.assistant import Conversation, ModelUnavailable, Route, ToolLedger, TravellerAssistant, Turn
from traveller_assistant.crew import CrewAiCrews, LlmRouter
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
