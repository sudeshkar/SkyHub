import { NextResponse, type NextRequest } from "next/server";
import { POLICIES, rateLimitRequest } from "@/lib/rate-limit";

/**
 * Rate-limits search page requests exactly once per request, before rendering. Server Actions
 * (booking, lookup) enforce their own limits inside the action.
 */
export async function proxy(request: NextRequest) {
  const decision = await rateLimitRequest(POLICIES.search, request.headers);
  if (decision.allowed) return NextResponse.next();

  return new NextResponse(
    `<!doctype html><html lang="en"><meta charset="utf-8"><title>Too many searches — SkyHub</title>` +
      `<body style="font-family:system-ui;max-width:32rem;margin:4rem auto;padding:0 1rem">` +
      `<h1>Too many searches</h1><p>Please wait ${decision.retryAfterSeconds} seconds and try again.</p>` +
      `<p><a href="/">Back to SkyHub</a></p></body></html>`,
    {
      status: 429,
      headers: { "Content-Type": "text/html; charset=utf-8", "Retry-After": String(decision.retryAfterSeconds) },
    },
  );
}

export const config = {
  matcher: [
    {
      source: "/search",
      // Router prefetches are not user searches and must not spend the user's budget.
      missing: [
        { type: "header", key: "next-router-prefetch" },
        { type: "header", key: "purpose", value: "prefetch" },
      ],
    },
  ],
};
