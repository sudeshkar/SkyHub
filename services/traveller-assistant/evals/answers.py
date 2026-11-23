"""python -m evals.answers: the whole assistant with the real model (ADR-0093).

The router, the crews, the traveller MCP server over stdio (started once) and Groq, with the code's check, on the
questions in answers.yml. Flight search is a stand-in on this machine serving fixed itineraries, so the flight answers
are known. Needs GROQ_API_KEY, from the environment or the repository's git-ignored .env; the key is never printed.
It spends the free tier's tokens and paces itself (``--pause``) to stay under the per-minute limit.
"""

from __future__ import annotations

import argparse
import contextlib
import json
import os
import re
import threading
import time
from collections.abc import Iterator
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import yaml

from tests.flight_world import BOM_JFK, DXB_LHR
from traveller_assistant.assistant import (FLIGHTS_FALLBACK, HELP_FALLBACK, Answer, Conversation, Route,
                                           TravellerAssistant)

QUESTIONS = Path(__file__).with_name("answers.yml")
ROOT = Path(__file__).resolve().parents[3]
ROUTES = {("BOM", "JFK"): BOM_JFK, ("DXB", "LHR"): DXB_LHR}
_FLIGHT = re.compile(r"\b[A-Z]{2}\d{1,4}\b")


def groq_key() -> str | None:
    if os.environ.get("GROQ_API_KEY"):
        return os.environ["GROQ_API_KEY"]
    env = ROOT / ".env"
    if env.is_file():
        for line in env.read_text(encoding="utf-8").splitlines():
            if line.startswith("GROQ_API_KEY=") and line.split("=", 1)[1].strip():
                return line.split("=", 1)[1].strip()
    return None


@contextlib.contextmanager
def stand_in_flight_search() -> Iterator[str]:
    """flight-search's itinerary search on 127.0.0.1, answering from the tests' fixed itineraries."""

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:   # noqa: N802 (the standard library's name)
            url = urlparse(self.path)
            query = {k: v[0] for k, v in parse_qs(url.query).items()}
            body = json.dumps(ROUTES.get((query.get("origin"), query.get("destination")), [])
                              if url.path == "/api/v1/itineraries" else []).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args) -> None:
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        yield f"http://127.0.0.1:{server.server_address[1]}"
    finally:
        server.shutdown()


@contextlib.contextmanager
def live_assistant(key: str, model: str | None = None) -> Iterator[TravellerAssistant]:
    from crewai_tools import MCPServerAdapter

    from traveller_assistant.crew import DEFAULT_MODEL, CrewAiCrews, LlmRouter, groq_llm
    from traveller_assistant.web import mcp_server_parameters

    # More patience than the service: an eval would rather wait out the per-minute limit than lose the measurement.
    llm = groq_llm(key, model or DEFAULT_MODEL, max_retries=6)
    with stand_in_flight_search() as url:
        adapter = MCPServerAdapter(mcp_server_parameters({"SKYHUB_SEARCH_API_URL": url}), connect_timeout=120)
        try:
            yield TravellerAssistant(LlmRouter(llm), CrewAiCrews(llm, list(adapter.tools)))
        finally:
            adapter.stop()


@dataclass
class Result:
    group: str
    question: str
    passed: bool
    answer: Answer
    why: str = ""


@dataclass
class Report:
    results: list[Result] = field(default_factory=list)

    def share(self, group: str) -> float:
        group_results = [r for r in self.results if r.group == group]
        return sum(r.passed for r in group_results) / len(group_results)

    def invented_flights(self) -> int:
        """Answers the check replaced because they named a flight the search never returned."""
        return sum(r.answer.reply == FLIGHTS_FALLBACK and r.answer.outcome == "unverified" for r in self.results)


def judge(group: str, case: dict | str, answer: Answer) -> tuple[bool, str]:
    if group == "help":
        if answer.outcome != "answered":
            return False, f"outcome {answer.outcome}"
        if case["article"] not in [s.article for s in answer.sources]:
            return False, f"sources {[s.article for s in answer.sources]}"
        return (case["says"] in answer.reply, "" if case["says"] in answer.reply else f"no '{case['says']}'")
    if group == "not_covered":
        return answer.reply in (HELP_FALLBACK, FLIGHTS_FALLBACK), f"outcome {answer.outcome}"
    if group == "off_topic":
        return answer.route is Route.OTHER, f"route {answer.route}"
    named = set(_FLIGHT.findall(answer.reply))
    if answer.outcome != "answered" or not named & set(case["flights"]):
        return False, f"outcome {answer.outcome}, named {sorted(named)}"
    return True, ""


def evaluate(assistant: TravellerAssistant, pause: float = 15.0, path: Path = QUESTIONS, echo=print) -> Report:
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    report = Report()
    for group in ("help", "not_covered", "off_topic", "flights"):
        for case in data[group]:
            question = case if isinstance(case, str) else case["question"]
            started = time.perf_counter()
            answer = assistant.answer(Conversation(question))
            passed, why = judge(group, case, answer)
            report.results.append(Result(group, question, passed, answer, why))
            echo(f"{'pass' if passed else 'FAIL':>4}  {group:<11} {answer.route.value:<7} {answer.outcome:<10} "
                 f"{time.perf_counter() - started:5.1f}s  {question}" + (f"   [{why}]" if not passed else ""))
            if answer.route is not Route.OTHER:
                time.sleep(pause)
    return report


def main() -> None:
    parser = argparse.ArgumentParser(prog="python -m evals.answers")
    parser.add_argument("--pause", type=float, default=15.0, help="seconds between questions (Groq's per-minute limit)")
    parser.add_argument("--model", default=None, help="a Groq model other than the default")
    parser.add_argument("--show", action="store_true", help="print every reply")
    arguments = parser.parse_args()
    key = groq_key()
    if not key:
        raise SystemExit("Set GROQ_API_KEY (a free Groq key), in the environment or the repository's .env.")
    with live_assistant(key, arguments.model) as assistant:
        report = evaluate(assistant, arguments.pause)
    if arguments.show:
        for r in report.results:
            print(f"\n[{r.group}] {r.question}\n{r.answer.reply}\nsources: {[s.article for s in r.answer.sources]}")
    print("\ngroup        passed")
    for group in ("help", "not_covered", "off_topic", "flights"):
        print(f"{group:<12} {report.share(group):6.0%}")
    print(f"answers replaced for naming a flight the search never returned: {report.invented_flights()}")


if __name__ == "__main__":
    main()
