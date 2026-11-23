"""Help-centre articles cut into passages for retrieval (ADR-0091): one per ``##`` section, plus the text before the
first section. Each passage carries its article's title and its own heading, so a short section still says what it is
about to the embedding model.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from traveller_assistant.help_centre import Article, HelpCentreError

MAX_WORDS = 150
"""The embedding model reads at most 256 tokens of a text and ignores the rest; 150 words fit with room to spare."""

_INTRO = "intro"


@dataclass(frozen=True)
class Passage:
    id: str
    article_id: str
    title: str
    heading: str
    """Empty for the text before the article's first section."""
    text: str

    @property
    def embedded_text(self) -> str:
        """What the model reads: the title and heading in front of the text."""
        if not self.heading:
            return f"{self.title}.\n{self.text}"
        heading = self.heading if self.heading[-1] in ".?!:" else self.heading + "."
        return f"{self.title}. {heading}\n{self.text}"

    @property
    def words(self) -> int:
        return len(self.embedded_text.split())


def passages_of(article: Article) -> list[Passage]:
    """The article's passages in reading order. A heading with nothing under it makes no passage."""
    sections: list[tuple[str, list[str]]] = [("", [])]
    for line in article.body.replace("\r\n", "\n").split("\n"):
        if line.startswith("## "):
            sections.append((line[3:].strip(), []))
        elif not line.startswith("# "):
            sections[-1][1].append(line)

    passages: list[Passage] = []
    seen: set[str] = set()
    for heading, lines in sections:
        text = "\n".join(lines).strip()
        if not text:
            continue
        slug = _slug(heading) if heading else _INTRO
        if slug in seen:
            raise HelpCentreError(f"{article.id}: two sections are called '{heading}'")
        seen.add(slug)
        passages.append(Passage(f"{article.id}#{slug}", article.id, article.title, heading, text))
    return passages


def _slug(heading: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", heading.lower()).strip("-")
