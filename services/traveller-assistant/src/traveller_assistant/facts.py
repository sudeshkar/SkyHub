"""Facts the help centre states, each tied to the line of SkyHub code that enforces it (ADR-0090).

A fact is a rule with a number: its value, the words an article uses to state it, and where the code says it. The
help-centre test checks both sides: the code still says the value, and an article states no number it has not
declared.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import yaml

_FIELDS = ("value", "says", "source", "pattern")
_NUMBER = re.compile(r"\d+")


class FactError(ValueError):
    """A fact that cannot be checked, or whose code no longer says it, with the fact named in the message."""


@dataclass(frozen=True)
class Fact:
    id: str
    value: int
    says: str
    """How an article states it, with ``{value}`` where the number goes: ``"{value} hours before"``."""
    source: str
    """The file that enforces it, from the repository root."""
    pattern: str
    """A regular expression with one group, which captures the value in ``source`` and must match exactly once."""

    @property
    def phrase(self) -> str:
        return self.says.format(value=self.value)

    def value_in_code(self, repo_root: Path) -> int:
        path = repo_root / self.source
        if not path.is_file():
            raise FactError(f"{self.id}: {self.source} does not exist")
        found = re.findall(self.pattern, path.read_text(encoding="utf-8"))
        if len(found) != 1:
            raise FactError(f"{self.id}: expected the pattern once in {self.source}, found {len(found)}")
        return int(found[0])


def load_facts(path: Path) -> dict[str, Fact]:
    """The registry, keyed by fact name, in the order it is written."""
    entries = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    if not isinstance(entries, dict):
        raise FactError(f"{path}: the registry must map fact names to their fields")
    return {name: _fact(name, fields) for name, fields in entries.items()}


def _fact(name: str, fields: object) -> Fact:
    if not isinstance(fields, dict):
        raise FactError(f"{name}: needs the fields {', '.join(_FIELDS)}")
    for field in _FIELDS:
        if field not in fields:
            raise FactError(f"{name}: '{field}' is missing")
    if not isinstance(fields["value"], int) or isinstance(fields["value"], bool):
        raise FactError(f"{name}: 'value' must be a whole number")
    if "{value}" not in str(fields["says"]):
        raise FactError(f"{name}: 'says' must contain {{value}}")
    try:
        groups = re.compile(fields["pattern"]).groups
    except re.error as error:
        raise FactError(f"{name}: the pattern is not a regular expression ({error})") from error
    if groups != 1:
        raise FactError(f"{name}: the pattern must have exactly one group, which captures the value")
    return Fact(name, fields["value"], str(fields["says"]), str(fields["source"]), fields["pattern"])


def unbacked_numbers(text: str, declared: Iterable[Fact]) -> list[str]:
    """The numbers in ``text`` that are not part of a declared fact stated in its registered words.

    Longer phrases are taken out first, so "24 hours or more before" is not left half-matched by "24 hours".
    """
    remaining = text
    for fact in sorted(declared, key=lambda f: len(f.phrase), reverse=True):
        remaining = remaining.replace(fact.phrase, " ")
    return _NUMBER.findall(remaining)
