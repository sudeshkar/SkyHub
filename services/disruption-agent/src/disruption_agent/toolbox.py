"""How the agent reaches tools. The agent depends on the `ToolBox` protocol only; MCP is one way to implement it."""
from __future__ import annotations

import contextlib
import json
from collections.abc import AsyncIterator
from typing import Protocol

from .llm import ToolSpec


class ToolBox(Protocol):
    async def specs(self) -> list[ToolSpec]: ...

    async def call(self, name: str, arguments: dict) -> dict: ...


@contextlib.asynccontextmanager
async def unwrapped() -> AsyncIterator[None]:
    """The MCP client runs inside task groups, which wrap any error in (nested) exception groups. Callers want the real
    error, so a group holding exactly one exception is unwrapped to it."""
    try:
        yield
    except BaseExceptionGroup as group:
        error: BaseException = group
        while isinstance(error, BaseExceptionGroup) and len(error.exceptions) == 1:
            error = error.exceptions[0]
        if error is group:
            raise
        raise error from None


class McpToolBox:
    """Adapter over an MCP client. A failed call (unknown tool, a crash inside a tool) comes back as `{"error": ...}`, the
    same shape the tools use for their own errors, so the agent handles one kind of failure."""

    def __init__(self, client):
        self._client = client

    async def specs(self) -> list[ToolSpec]:
        listed = await self._client.list_tools()
        return [ToolSpec(name=t.name, description=t.description or "", parameters=t.input_schema) for t in listed.tools]

    async def call(self, name: str, arguments: dict) -> dict:
        result = await self._client.call_tool(name, arguments)
        text = next((block.text for block in result.content if hasattr(block, "text")), "")
        if result.is_error:
            return {"error": text or f"The tool {name} failed"}
        if result.structured_content:
            return result.structured_content
        try:
            payload = json.loads(text)
        except json.JSONDecodeError:
            return {"error": f"The tool {name} returned something that is not JSON"}
        return payload if isinstance(payload, dict) else {"result": payload}
