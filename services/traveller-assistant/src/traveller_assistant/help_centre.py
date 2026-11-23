"""The help centre the assistant answers from (ADR-0090): one Markdown article per topic, each with a small header.

What this module returns is what retrieval indexes, so the assistant reads exactly the articles the tests checked.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

import yaml

_ARTICLE = re.compile(r"\A---\r?\n(.*?)\r?\n---\r?\n(.*)\Z", re.DOTALL)
_REQUIRED = ("id", "title", "summary")
_ALLOWED = frozenset(_REQUIRED + ("facts",))


class HelpCentreError(ValueError):
    """An article that cannot be used, with the article named in the message."""


@dataclass(frozen=True)
class Article:
    id: str
    title: str
    summary: str
    facts: tuple[str, ...]
    body: str


def parse_article(text: str, name: str) -> Article:
    """Reads one article; ``name`` is its file name without ``.md`` and must equal its id."""
    match = _ARTICLE.match(text)
    if match is None:
        raise HelpCentreError(f"{name}: no header (the article must start with a '---' block)")
    header = yaml.safe_load(match.group(1)) or {}
    if not isinstance(header, dict):
        raise HelpCentreError(f"{name}: the header must be 'field: value' lines")
    unknown = sorted(set(header) - _ALLOWED)
    if unknown:
        raise HelpCentreError(f"{name}: unknown header field '{unknown[0]}'")
    for field in _REQUIRED:
        if not isinstance(header.get(field), str) or not header[field].strip():
            raise HelpCentreError(f"{name}: '{field}' is missing")
    if header["id"] != name:
        raise HelpCentreError(f"{name}: the id is '{header['id']}', but it must be the file name")
    facts = header.get("facts", [])
    if not isinstance(facts, list) or not all(isinstance(f, str) for f in facts):
        raise HelpCentreError(f"{name}: 'facts' must be a list of fact names")
    body = match.group(2).replace("\r\n", "\n")
    if not body.strip():
        raise HelpCentreError(f"{name}: the article is empty")
    return Article(header["id"], header["title"].strip(), header["summary"].strip(), tuple(facts), body)


def load_articles(directory: Path) -> list[Article]:
    """Every ``*.md`` article in ``directory``, in file-name order. Ids are unique because they are the file names."""
    paths = sorted(directory.glob("*.md"))
    if not paths:
        raise HelpCentreError(f"{directory}: no articles")
    return [parse_article(path.read_text(encoding="utf-8"), path.stem) for path in paths]
