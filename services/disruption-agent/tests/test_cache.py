from disruption_agent.cache import TtlCache


class Clock:
    def __init__(self):
        self.now = 0.0

    def __call__(self) -> float:
        return self.now


def test_computes_a_value_once_and_then_serves_it_from_memory():
    cache, calls = TtlCache(ttl_seconds=60, max_entries=10, clock=Clock()), []

    for _ in range(5):
        cache.get_or_compute("k", lambda: calls.append(1) or "value")

    assert len(calls) == 1


def test_forgets_a_value_when_its_time_is_up_so_long_running_servers_do_not_serve_stale_data():
    clock = Clock()
    cache, calls = TtlCache(ttl_seconds=30, max_entries=10, clock=clock), []
    compute = lambda: calls.append(1) or len(calls)

    assert cache.get_or_compute("seats", compute) == 1
    clock.now = 29
    assert cache.get_or_compute("seats", compute) == 1
    clock.now = 31
    assert cache.get_or_compute("seats", compute) == 2


def test_never_holds_more_than_its_limit_and_drops_the_least_recently_used_first():
    cache = TtlCache(ttl_seconds=60, max_entries=3, clock=Clock())
    for key in "abc":
        cache.get_or_compute(key, lambda key=key: key)
    cache.get_or_compute("a", lambda: "recomputed")   # touching "a" makes "b" the oldest
    cache.get_or_compute("d", lambda: "d")

    assert len(cache) == 3
    assert cache.get_or_compute("a", lambda: "recomputed") == "a"
    assert cache.get_or_compute("b", lambda: "recomputed") == "recomputed"


def test_does_not_cache_a_failure():
    cache, attempts = TtlCache(ttl_seconds=60, max_entries=3, clock=Clock()), []

    def flaky():
        attempts.append(1)
        if len(attempts) == 1:
            raise RuntimeError("down")
        return "ok"

    try:
        cache.get_or_compute("k", flaky)
    except RuntimeError:
        pass

    assert cache.get_or_compute("k", flaky) == "ok"
