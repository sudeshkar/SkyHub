import { readAccountSession } from "@/lib/account-session";
import { listSavedPassengers } from "@/lib/api/booking";
import { POLICIES, rateLimit } from "@/lib/rate-limit";

const NO_STORE = { "Cache-Control": "no-store" };

/**
 * The signed-in traveller's saved names for the booking form (ADR-0078). It lives under /account because the account cookie is scoped to
 * that path (ADR-0033): the booking page cannot read the cookie, so its browser code asks here. It gives only the names, no ids and no
 * address, and the same empty list to someone who is not signed in, whose session has ended or when the booking service does not answer,
 * so it says nothing about who is signed in. Never cached.
 */
export async function GET(): Promise<Response> {
  const decision = await rateLimit(POLICIES.manage);
  if (!decision.allowed) {
    return Response.json({ passengers: [] }, { status: 429, headers: { ...NO_STORE, "Retry-After": String(decision.retryAfterSeconds) } });
  }
  const session = await readAccountSession();
  const result = session ? await listSavedPassengers(session.token) : null;
  const passengers = result?.kind === "list" ? result.passengers.map(({ firstName, lastName }) => ({ firstName, lastName })) : [];
  return Response.json({ passengers }, { headers: NO_STORE });
}
