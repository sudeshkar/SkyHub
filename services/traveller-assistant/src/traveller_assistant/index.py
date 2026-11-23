"""The help centre's passages in an in-memory Chroma collection (ADR-0091).

Built from the articles at start, so it is always exactly what the ADR-0090 test checked. About fifty passages: a few
seconds to embed. At thousands of articles this would need the index built into the image instead (see the ADR).
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import chromadb
from chromadb.config import Settings

from traveller_assistant.embedding import Embedder
from traveller_assistant.help_centre import load_articles
from traveller_assistant.passages import Passage, passages_of

HELP_CENTRE = Path(__file__).resolve().parents[2] / "help-centre"

DEFAULT_MAX_DISTANCE = 0.70
"""Cosine distance beyond which a passage is not an answer. Set from the evals (``python -m evals``); see the ADR."""


@dataclass(frozen=True)
class Found:
    article_id: str
    title: str
    heading: str
    text: str
    distance: float
    """Cosine distance from the question: 0 is the same direction, 2 the opposite."""


def chroma_settings() -> Settings:
    """No usage telemetry: SkyHub sends nothing to third parties it does not need to."""
    return Settings(anonymized_telemetry=False)


class HelpCentreIndex:
    def __init__(self, passages: Iterable[Passage], embedder: Embedder, max_distance: float = DEFAULT_MAX_DISTANCE):
        self.passages = tuple(passages)
        self._embedder = embedder
        self._max_distance = max_distance
        client = chromadb.EphemeralClient(settings=chroma_settings())
        # A collection of its own, so two indexes in one process (tests, a reload) never share passages. No embedding
        # function of Chroma's: vectors come only from the Embedder, so nothing loads a model behind its back.
        self._collection = client.create_collection(name=f"help-centre-{uuid.uuid4().hex}", embedding_function=None,
                                                    configuration={"hnsw": {"space": "cosine"}})
        if self.passages:
            self._collection.add(
                ids=[p.id for p in self.passages],
                embeddings=embedder.embed([p.embedded_text for p in self.passages]),
                documents=[p.text for p in self.passages],
                metadatas=[{"article_id": p.article_id, "title": p.title, "heading": p.heading} for p in self.passages])

    def __len__(self) -> int:
        return len(self.passages)

    def search(self, question: str, limit: int) -> list[Found]:
        """At most ``limit`` passages, nearest first, none farther than ``max_distance``."""
        if not self.passages:
            return []
        result = self._collection.query(query_embeddings=self._embedder.embed([question]),
                                        n_results=min(limit, len(self.passages)),
                                        include=["documents", "metadatas", "distances"])
        found = []
        for text, meta, distance in zip(result["documents"][0], result["metadatas"][0], result["distances"][0]):
            if distance <= self._max_distance:
                found.append(Found(str(meta["article_id"]), str(meta["title"]), str(meta["heading"]), text, float(distance)))
        return found


def build_index(embedder: Embedder, directory: Path = HELP_CENTRE,
                max_distance: float = DEFAULT_MAX_DISTANCE) -> HelpCentreIndex:
    """The index of every article in the help centre."""
    return HelpCentreIndex([p for a in load_articles(directory) for p in passages_of(a)], embedder, max_distance)
