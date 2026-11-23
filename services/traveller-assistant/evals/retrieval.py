"""Measures retrieval over the real help centre with the real model (ADR-0091).

One index is built with no distance cut-off, so the raw ranking and distances can be reported; the cut-off the
assistant uses is then applied here, so the numbers are what the assistant would see.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import yaml

from traveller_assistant.index import DEFAULT_MAX_DISTANCE, Found, HelpCentreIndex

QUESTIONS = Path(__file__).with_name("questions.yml")
LIMIT = 3


@dataclass(frozen=True)
class Answerable:
    question: str
    articles: tuple[str, ...]
    found: tuple[Found, ...]
    """The top results with no cut-off, nearest first."""

    def rank(self, max_distance: float) -> int | None:
        """1 for the first passage, None if no passage from a right article is kept."""
        kept = [f for f in self.found if f.distance <= max_distance]
        for position, found in enumerate(kept, start=1):
            if found.article_id in self.articles:
                return position
        return None


@dataclass(frozen=True)
class Unanswerable:
    question: str
    nearest: Found

    def rejected(self, max_distance: float) -> bool:
        return self.nearest.distance > max_distance


@dataclass(frozen=True)
class Report:
    answerable: tuple[Answerable, ...]
    off_topic: tuple[Unanswerable, ...]
    not_covered: tuple[Unanswerable, ...]

    def hit_at(self, k: int, max_distance: float = DEFAULT_MAX_DISTANCE) -> float:
        hits = [a for a in self.answerable if (r := a.rank(max_distance)) is not None and r <= k]
        return len(hits) / len(self.answerable)

    @staticmethod
    def rejected(questions: tuple[Unanswerable, ...], max_distance: float = DEFAULT_MAX_DISTANCE) -> float:
        return sum(u.rejected(max_distance) for u in questions) / len(questions)


def load_questions(path: Path = QUESTIONS) -> dict:
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    answerable = []
    for entry in data["answerable"]:
        articles = entry["article"]
        answerable.append((entry["question"], tuple(articles) if isinstance(articles, list) else (articles,)))
    return {"answerable": answerable, "off_topic": list(data["off_topic"]), "not_covered": list(data["not_covered"])}


def evaluate(index: HelpCentreIndex, path: Path = QUESTIONS) -> Report:
    """``index`` must have no cut-off of its own (a ``max_distance`` of 2), so every distance is seen."""
    questions = load_questions(path)

    def unanswerable(group: str) -> tuple[Unanswerable, ...]:
        return tuple(Unanswerable(q, index.search(q, 1)[0]) for q in questions[group])

    return Report(
        tuple(Answerable(q, articles, tuple(index.search(q, LIMIT))) for q, articles in questions["answerable"]),
        unanswerable("off_topic"), unanswerable("not_covered"))
