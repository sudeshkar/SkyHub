"""python -m evals [--max-distance 0.70]: retrieval over the real help centre, question by question (ADR-0091).

Shows where each question's right article came, how far away the best passage was, and the totals at a range of
cut-offs, so the cut-off in ``traveller_assistant.index`` can be chosen from numbers.
"""

import argparse

from evals.retrieval import Report, evaluate
from traveller_assistant.embedding import MiniLmEmbedder
from traveller_assistant.index import DEFAULT_MAX_DISTANCE, build_index


def main() -> None:
    parser = argparse.ArgumentParser(prog="python -m evals")
    parser.add_argument("--max-distance", type=float, default=DEFAULT_MAX_DISTANCE)
    cut = parser.parse_args().max_distance

    report = evaluate(build_index(MiniLmEmbedder(), max_distance=2.0))

    print(f"Answerable ({len(report.answerable)}), cut-off {cut}:")
    for a in report.answerable:
        rank = a.rank(cut)
        best = a.found[0]
        print(f"  {'miss' if rank is None else f'#{rank}':>4}  {best.distance:.3f}  {best.article_id:<30} {a.question}")
    for title, group in (("Off topic", report.off_topic), ("Not covered by the help centre", report.not_covered)):
        print(f"\n{title} ({len(group)}):")
        for u in group:
            print(f"  {'none' if u.rejected(cut) else 'kept':>4}  {u.nearest.distance:.3f}  {u.nearest.article_id:<30} {u.question}")

    print("\ncut-off   hit@1   hit@3   off topic returned nothing   not covered returned nothing")
    for c in sorted({0.5, 0.55, 0.6, 0.65, 0.7, 0.75, 0.8, cut}):
        print(f"  {c:.2f}    {report.hit_at(1, c):5.0%}   {report.hit_at(3, c):5.0%}   "
              f"{Report.rejected(report.off_topic, c):10.0%}                  {Report.rejected(report.not_covered, c):10.0%}"
              + ("   <- in use" if c == cut else ""))


if __name__ == "__main__":
    main()
