import { createClient } from "redis";
import type { CounterStore } from "./sliding-window";

function connect() {
  return createClient({
    url: process.env.REDIS_URL ?? "redis://localhost:6379",
    socket: { connectTimeout: 1_000, reconnectStrategy: (retries) => Math.min(retries * 200, 5_000) },
  })
    .on("error", () => {
      // Reconnects on its own; callers fail open while it is down (see rateLimit()).
    })
    .connect();
}

// One client per server process, reused by every request; cached on globalThis so dev hot reloads
// don't open a new connection each time.
const globalCache = globalThis as unknown as { skyhubRedis?: ReturnType<typeof connect> };

function client(): ReturnType<typeof connect> {
  if (!globalCache.skyhubRedis) {
    globalCache.skyhubRedis = connect().catch((error: unknown) => {
      // Forget a failed first connection, so the next request tries again instead of failing forever.
      globalCache.skyhubRedis = undefined;
      throw error;
    });
  }
  return globalCache.skyhubRedis;
}

export class RedisCounterStore implements CounterStore {
  async hit(currentKey: string, previousKey: string, ttlSeconds: number) {
    const redis = await client();
    // One round trip, executed atomically.
    const [current, , previous] = await redis
      .multi()
      .incr(currentKey)
      .expire(currentKey, ttlSeconds)
      .get(previousKey)
      .exec();
    return { current: Number(current), previous: Number(previous ?? 0) };
  }
}
