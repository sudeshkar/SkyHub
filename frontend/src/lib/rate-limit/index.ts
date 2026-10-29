import { createHash } from "node:crypto";
import { headers } from "next/headers";
import { RedisCounterStore } from "./redis-store";
import { createRateLimiter, type Decision, type Policy } from "./sliding-window";

export { POLICIES } from "./sliding-window";
export type { Decision } from "./sliding-window";

const check = createRateLimiter(new RedisCounterStore());
const LIMIT_TIMEOUT_MS = 300;

/**
 * Client identity for limiting. X-Forwarded-For is only trusted behind our own load balancer
 * (SKYHUB_TRUST_PROXY=true); otherwise anyone could spoof it. The IP is hashed: no raw IPs in Redis.
 */
function clientId(requestHeaders: Headers): string {
  const forwarded =
    process.env.SKYHUB_TRUST_PROXY === "true" ? requestHeaders.get("x-forwarded-for")?.split(",")[0]?.trim() : null;
  return createHash("sha256")
    .update(forwarded || "direct")
    .digest("hex")
    .slice(0, 16);
}

/**
 * Counts one request against a policy. Call it once per request, from proxy.ts or a Server Action,
 * never during render: render code can run more than once, which would double-count.
 * Fails open: if Redis is slow or down, requests go through rather than taking the site down; the
 * backend still protects itself (validation, idempotency, optimistic locking).
 */
export async function rateLimitRequest(policy: Policy, requestHeaders: Headers): Promise<Decision> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<Decision>((resolve) => {
    timer = setTimeout(() => resolve({ allowed: true }), LIMIT_TIMEOUT_MS);
  });
  try {
    return await Promise.race([
      check(policy, clientId(requestHeaders)).catch((): Decision => ({ allowed: true })),
      timeout,
    ]);
  } finally {
    clearTimeout(timer);
  }
}

/** For Server Actions, which read the incoming request's headers via next/headers. */
export async function rateLimit(policy: Policy): Promise<Decision> {
  return rateLimitRequest(policy, await headers());
}
