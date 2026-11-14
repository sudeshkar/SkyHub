"""`python -m evals` prints how each planner does on each scenario. With `--live` the Groq model is judged against the
rule-based baselines on the same worlds (needs GROQ_API_KEY).

Planners: first-fit proposes per booking (ADR-0022); groups is the rule-based planner by group, where code fills the flights
it chooses (ADR-0089); groq is the language model by group. `--compare` also runs groq-each, the model per booking as before.

Columns: coverage is the share of bookings given a flight; people is the share of passengers; refused counts suggestions the guardrails turned down (a
sound planner has none, and for a language model this is the number to watch); unsafe is what the independent oracle
still found wrong in the final answer (it must always be 0)."""
from __future__ import annotations

import argparse
import logging
import os
import sys
import time

from disruption_agent.config import DEFAULT_GROQ_MODEL
from disruption_agent.llm import GroqChatModel
from disruption_agent.firstfit import FirstFitPlanner, GroupFirstFitPlanner
from disruption_agent.strategies import ByGroup, PerBooking
from evals.oracle import violations
from evals.scenarios import Scenario, installed, standard
from tests.harness import run_agent


def _row(planner: str, scenario: Scenario, model, strategy=None, verbose: bool = False) -> str:
    started = time.perf_counter()
    with installed(scenario) as world:
        report = run_agent(model, world, strategy=strategy or PerBooking())
        unsafe = len(violations(report, scenario.bookings, world.seats))
    coverage = report.rebooked / report.bookings_total if report.bookings_total else 1.0
    everyone = sum(p.passenger_count or 0 for p in report.proposals)
    moved = sum(p.passenger_count or 0 for p in report.proposals if p.action == "REBOOK")
    people = moved / everyone if everyone else 1.0
    row = (f"{scenario.name:<30} {planner:<10} {coverage:>9.0%} {people:>7.0%} {len(report.rejections):>8} {unsafe:>7} "
           f"{time.perf_counter() - started:>7.1f}s")
    if verbose:    # why the guardrails refused each suggestion: the thing to read when a model scores badly
        row += "".join(f"\n    refused {r.locator}: {r.reason}" for r in report.rejections)
    return row


def main(argv: list[str] | None = None) -> int:
    logging.getLogger("httpx").setLevel(logging.WARNING)
    parser = argparse.ArgumentParser(prog="evals", description=__doc__)
    parser.add_argument("--live", action="store_true", help="also run the Groq model (needs GROQ_API_KEY)")
    parser.add_argument("--verbose", action="store_true", help="print the reason for every refused suggestion")
    parser.add_argument("--compare", action="store_true", help="with --live, also run the model per booking (groq-each)")
    parser.add_argument("--only", action="append", metavar="NAME", help="run only this scenario (repeat for several); live runs are slow")
    args = parser.parse_args(argv)

    scenarios = standard()
    if args.only:
        known = {s.name for s in scenarios}
        unknown = [name for name in args.only if name not in known]
        if unknown:
            print(f"No scenario called {', '.join(map(repr, unknown))}. Scenarios: {', '.join(sorted(known))}", file=sys.stderr)
            return 2
        scenarios = [s for s in scenarios if s.name in args.only]

    live = None
    if args.live:
        key = os.environ.get("GROQ_API_KEY")
        if not key:
            print("Set GROQ_API_KEY to use --live.", file=sys.stderr)
            return 2
        from groq import Groq
        # The free tier allows 8,000 tokens a minute: wait it out (the client honours retry-after) rather than lose a measurement.
        live = GroqChatModel(Groq(api_key=key, max_retries=8), os.environ.get("GROQ_MODEL", DEFAULT_GROQ_MODEL))

    print(f"{'scenario':<30} {'planner':<10} {'coverage':>9} {'people':>7} {'refused':>8} {'unsafe':>7} {'time':>8}")
    for scenario in scenarios:
        print(_row("first-fit", scenario, FirstFitPlanner(), verbose=args.verbose))
        print(_row("groups", scenario, GroupFirstFitPlanner(), ByGroup(), verbose=args.verbose))
        if live is not None:
            if args.compare:
                print(_row("groq-each", scenario, live, verbose=args.verbose), flush=True)
            print(_row("groq", scenario, live, ByGroup(), verbose=args.verbose), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
