"""A small cache with two limits: how long a value is trusted, and how many are kept."""
from __future__ import annotations

import threading
import time
from collections import OrderedDict
from collections.abc import Callable
from typing import Generic, TypeVar

K = TypeVar("K")
V = TypeVar("V")


class TtlCache(Generic[K, V]):
    """Least-recently-used cache whose entries also expire.

    Both limits matter for a long-running tool server: the time limit keeps seat counts from going stale, and the
    size limit keeps memory bounded however many different flights are asked about. Every operation is O(1)
    (an ordered dict moves a touched key to the end and drops from the front). A lock makes it safe for the worker
    threads the MCP server runs tools on. A value is computed outside the lock, so a slow lookup never blocks
    others; two threads asking for the same new key at once may both compute it, which is harmless for reads.
    """

    def __init__(self, ttl_seconds: float, max_entries: int, clock: Callable[[], float] = time.monotonic):
        if ttl_seconds <= 0 or max_entries <= 0:
            raise ValueError("ttl_seconds and max_entries must be positive")
        self._ttl = ttl_seconds
        self._max = max_entries
        self._clock = clock
        self._entries: OrderedDict[K, tuple[float, V]] = OrderedDict()
        self._lock = threading.Lock()

    def get_or_compute(self, key: K, compute: Callable[[], V]) -> V:
        now = self._clock()
        with self._lock:
            hit = self._entries.get(key)
            if hit is not None and now - hit[0] < self._ttl:
                self._entries.move_to_end(key)
                return hit[1]
        value = compute()  # a failure propagates and is not cached
        with self._lock:
            self._entries[key] = (self._clock(), value)
            self._entries.move_to_end(key)
            while len(self._entries) > self._max:
                self._entries.popitem(last=False)
        return value

    def __len__(self) -> int:
        with self._lock:
            return len(self._entries)
