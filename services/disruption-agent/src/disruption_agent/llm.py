"""The language model, behind a port. The agent talks to `ChatModel`; Groq is one adapter, a scripted model in tests is
another, and a different provider would be a third without touching the agent."""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Protocol


@dataclass(frozen=True)
class ToolSpec:
    name: str
    description: str
    parameters: dict


@dataclass(frozen=True)
class ToolCall:
    id: str
    name: str
    arguments: dict


@dataclass(frozen=True)
class Turn:
    """What the model said: text, tool requests, or both."""
    content: str | None
    tool_calls: list[ToolCall] = field(default_factory=list)


class ChatModel(Protocol):
    def complete(self, messages: list[dict], tools: list[ToolSpec]) -> Turn: ...


INVALID_JSON = "__invalid_json__"
MALFORMED_ATTEMPTS = 3


class GroqChatModel:
    """Adapter over Groq's OpenAI-compatible chat API. Temperature 0 so the same disruption gets the same answer."""

    def __init__(self, client, model: str, temperature: float = 0):
        self._client = client
        self._model = model
        self._temperature = temperature

    def complete(self, messages: list[dict], tools: list[ToolSpec]) -> Turn:
        request: dict = {"model": self._model, "messages": messages, "temperature": self._temperature}
        if tools:
            request["tools"] = [{"type": "function", "function": {
                "name": t.name, "description": t.description, "parameters": t.parameters}} for t in tools]
        for _ in range(MALFORMED_ATTEMPTS):
            try:
                message = self._client.chat.completions.create(**request).choices[0].message
            except Exception as error:
                if not _malformed_tool_call(error):
                    raise          # a missing model, a bad key, an outage: a person must see these
                continue           # Groq checks the call against our schema and refused it: the model slipped, so ask again
            return Turn(content=message.content, tool_calls=[
                ToolCall(id=c.id, name=c.function.name, arguments=_arguments(c.function.arguments))
                for c in (message.tool_calls or [])])
        return Turn(content=None)  # still malformed: no answer, so the agent hands the batch to a person


def _malformed_tool_call(error: Exception) -> bool:
    return getattr(error, "status_code", None) == 400 and "tool_use_failed" in str(error)


def _arguments(raw: str) -> dict:
    try:
        parsed = json.loads(raw or "{}")
    except json.JSONDecodeError:
        return {INVALID_JSON: raw}   # the agent tells the model its arguments were not JSON, so it can try again
    return parsed if isinstance(parsed, dict) else {INVALID_JSON: raw}
