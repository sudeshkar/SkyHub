"""Turning text into vectors (ADR-0091). The index depends on the ``Embedder`` port only, so another model is another
adapter, and tests use a fake that needs no download."""

from __future__ import annotations

from typing import Protocol


class Embedder(Protocol):
    def embed(self, texts: list[str]) -> list[list[float]]:
        """One vector per text, in the same order."""
        ...


class MiniLmEmbedder:
    """all-MiniLM-L6-v2, run locally by Chroma (ONNX): free and needs no key.

    The first use downloads the model (about 80 MB) to ``~/.cache/chroma``; Chroma checks it against a fixed SHA-256.
    It reads at most 256 tokens of each text, which is why passages are bounded (``passages.MAX_WORDS``).
    """

    def __init__(self) -> None:
        from chromadb.utils.embedding_functions import DefaultEmbeddingFunction

        self._model = DefaultEmbeddingFunction()

    def embed(self, texts: list[str]) -> list[list[float]]:
        return [[float(x) for x in vector] for vector in self._model(list(texts))]
