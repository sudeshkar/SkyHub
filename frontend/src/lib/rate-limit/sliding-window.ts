/**
 * Sliding-window-counter rate limiting: O(1) memory per client (two counters), without the 2× burst
 * a fixed window allows at its edges. The estimate weights the previous window by how much of it
 * still overlaps the sliding window:  previous × (1 − elapsed / window) + current.
 */

export type Policy = { name: string; limit: number; windowMs: number };

export type Decision = { allowed: true } | { allowed: false; retryAfterSeconds: number };

/** Increments the current window's counter and reads the previous one, atomically where possible. */
export interface CounterStore {
  hit(currentKey: string, previousKey: string, ttlSeconds: number): Promise<{ current: number; previous: number }>;
}

export const POLICIES = {
  /** Search is cheap and cached, so generous. */
  search: { name: "search", limit: 60, windowMs: 60_000 },
  /** Booking writes rows and holds seats. */
  booking: { name: "booking", limit: 5, windowMs: 60_000 },
  /** Holds lock seats for ten minutes, so they are tight: a script must not be able to lock a whole flight. */
  hold: { name: "hold", limit: 6, windowMs: 60_000 },
  /** Lookup is the brute-force target: 6-character locators guessed with common surnames. */
  lookup: { name: "lookup", limit: 10, windowMs: 15 * 60_000 },
  /** Cancel and change-seat, after a session is open: generous for a person, tight for a script. */
  manage: { name: "manage", limit: 30, windowMs: 60_000 },
  /** Staff login is a password-guessing target; the service also locks an account after five misses (ADR-0017). */
  staffLogin: { name: "staff-login", limit: 10, windowMs: 15 * 60_000 },
  /** Each request can send an email, so it is tight: a script must not be able to flood an inbox (ADR-0033). */
  accountLink: { name: "account-link", limit: 5, windowMs: 15 * 60_000 },
  /** Traveller sign-in is a password-guessing target; the service also locks an account after five misses. */
  accountSignIn: { name: "account-sign-in", limit: 10, windowMs: 15 * 60_000 },
} as const satisfies Record<string, Policy>;

export function createRateLimiter(store: CounterStore, now: () => number = Date.now) {
  return async function check(policy: Policy, clientId: string): Promise<Decision> {
    const time = now();
    const window = Math.floor(time / policy.windowMs);
    const elapsed = (time % policy.windowMs) / policy.windowMs;
    const prefix = `rl:${policy.name}:${clientId}`;

    const { current, previous } = await store.hit(
      `${prefix}:${window}`,
      `${prefix}:${window - 1}`,
      Math.ceil((2 * policy.windowMs) / 1000),
    );

    const estimate = previous * (1 - elapsed) + current;
    if (estimate <= policy.limit) return { allowed: true };
    return { allowed: false, retryAfterSeconds: Math.max(1, Math.ceil(((1 - elapsed) * policy.windowMs) / 1000)) };
  };
}

/** In-process store for tests and single-instance development. */
export class MemoryCounterStore implements CounterStore {
  private readonly counters = new Map<string, number>();

  async hit(currentKey: string, previousKey: string) {
    const current = (this.counters.get(currentKey) ?? 0) + 1;
    this.counters.set(currentKey, current);
    return { current, previous: this.counters.get(previousKey) ?? 0 };
  }
}
