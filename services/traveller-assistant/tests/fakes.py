"""A stand-in for the embedding model, so tests need no download and run in milliseconds.

Every word gets a dimension of its own the first time it is seen, so texts that share words are close and texts that
share none are exactly orthogonal (cosine distance 1): enough to test ranking and the distance cut-off without testing
the real model (the evals do that).
"""

import math
import re


class WordsEmbedder:
    def __init__(self, dimensions: int = 4096):
        self.dimensions = dimensions
        self.vocabulary: dict[str, int] = {}
        self.calls: list[list[str]] = []

    def embed(self, texts: list[str]) -> list[list[float]]:
        self.calls.append(list(texts))
        return [self._vector(text) for text in texts]

    def _vector(self, text: str) -> list[float]:
        vector = [0.0] * self.dimensions
        for word in re.findall(r"[a-z]+", text.lower()):
            # Dimension 0 is kept for a text with no words at all.
            vector[self.vocabulary.setdefault(word, len(self.vocabulary) + 1)] += 1.0
        if not any(vector):
            vector[0] = 1.0
        length = math.sqrt(sum(v * v for v in vector))
        return [v / length for v in vector]
