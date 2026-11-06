"""Settings come from the environment, so no key or password is ever written into a file."""
from __future__ import annotations

import os
from dataclasses import dataclass

import httpx

from .clients import BookingClient, SearchClient

TIMEOUT_SECONDS = 5.0
# The one place the default model is named. Groq retires models (llama-3.3-70b-versatile went away in 2026): set GROQ_MODEL, or change this.
DEFAULT_GROQ_MODEL = "openai/gpt-oss-120b"


@dataclass(frozen=True)
class Settings:
    booking_url: str
    search_url: str
    agent_username: str
    agent_password: str
    groq_model: str

    @classmethod
    def from_env(cls) -> "Settings":
        missing = [name for name in ("SKYHUB_AGENT_USERNAME", "SKYHUB_AGENT_PASSWORD") if not os.environ.get(name)]
        if missing:
            raise SystemExit(f"Set {', '.join(missing)}: an OPS staff account for the agent (see the README).")
        return cls(booking_url=os.environ.get("SKYHUB_BOOKING_API_URL", "http://localhost:8082"),
                   search_url=os.environ.get("SKYHUB_SEARCH_API_URL", "http://localhost:8081"),
                   agent_username=os.environ["SKYHUB_AGENT_USERNAME"], agent_password=os.environ["SKYHUB_AGENT_PASSWORD"],
                   groq_model=os.environ.get("GROQ_MODEL", DEFAULT_GROQ_MODEL))

    def booking_client(self) -> BookingClient:
        return BookingClient(httpx.Client(base_url=self.booking_url, timeout=TIMEOUT_SECONDS),
                             self.agent_username, self.agent_password)

    def search_client(self) -> SearchClient:
        return SearchClient(httpx.Client(base_url=self.search_url, timeout=TIMEOUT_SECONDS),
                            httpx.Client(base_url=self.booking_url, timeout=TIMEOUT_SECONDS))
