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
        message = self._client.chat.completions.create(**request).choices[0].message
        return Turn(content=message.content, tool_calls=[
            ToolCall(id=c.id, name=c.function.name, arguments=_arguments(c.function.arguments))
            for c in (message.tool_calls or [])])


def _arguments(raw: str) -> dict:
    try:
        parsed = json.loads(raw or "{}")
    except json.JSONDecodeError:
        return {INVALID_JSON: raw}   # the agent tells the model its arguments were not JSON, so it can try again
    return parsed if isinstance(parsed, dict) else {INVALID_JSON: raw}
