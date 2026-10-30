"""`python -m evals` prints how each planner does on each scenario. With `--live` the Groq model is judged against the
rule-based baseline on the same worlds (needs GROQ_API_KEY).

Columns: coverage is the share of bookings given a flight; refused counts suggestions the guardrails turned down (a
sound planner has none, and for a language model this is the number to watch); unsafe is what the independent oracle
still found wrong in the final answer (it must always be 0)."""
from __future__ import annotations

import argparse
import logging
import os
import sys
import time

from disruption_agent.llm import GroqChatModel
from evals.baseline import FirstFitModel
from evals.oracle import violations
from evals.scenarios import Scenario, installed, standard
from tests.harness import run_agent


def _row(planner: str, scenario: Scenario, model) -> str:
    started = time.perf_counter()
    with installed(scenario) as world:
        report = run_agent(model, world)
        unsafe = len(violations(report, scenario.bookings, world.seats))
    coverage = report.rebooked / report.bookings_total if report.bookings_total else 1.0
    return (f"{scenario.name:<30} {planner:<10} {coverage:>9.0%} {len(report.rejections):>8} {unsafe:>7} "
            f"{time.perf_counter() - started:>7.1f}s")


def main(argv: list[str] | None = None) -> int:
    logging.getLogger("httpx").setLevel(logging.WARNING)
    parser = argparse.ArgumentParser(prog="evals", description=__doc__)
    parser.add_argument("--live", action="store_true", help="also run the Groq model (needs GROQ_API_KEY)")
    args = parser.parse_args(argv)

    live = None
    if args.live:
        key = os.environ.get("GROQ_API_KEY")
        if not key:
            print("Set GROQ_API_KEY to use --live.", file=sys.stderr)
            return 2
        from groq import Groq
        live = GroqChatModel(Groq(api_key=key), os.environ.get("GROQ_MODEL", "llama-3.3-70b-versatile"))

    print(f"{'scenario':<30} {'planner':<10} {'coverage':>9} {'refused':>8} {'unsafe':>7} {'time':>8}")
    for scenario in standard():
        print(_row("first-fit", scenario, FirstFitModel()))
        if live is not None:
            print(_row("groq", scenario, live))
    return 0


if __name__ == "__main__":
    sys.exit(main())
