/** A saved name as the booking form uses it: nothing else about the person is held, here or anywhere. */
export type SavedName = { firstName: string; lastName: string };

/** An account keeps at most this many, so a reply with more is not believed (ADR-0078). */
const MAX = 10;

/**
 * Asks this site for the signed-in traveller's saved names (ADR-0078), from the booking page's browser code. The account's cookie is
 * scoped to /account on purpose (ADR-0033), so the booking page cannot read it; this goes to a route under /account, which can. It never
 * fails the booking: anyone who is not signed in, a service that does not answer and an answer that is not what was expected all give no
 * names, and the form is as it always was.
 */
export async function loadSavedPassengers(fetcher: typeof fetch = fetch): Promise<SavedName[]> {
  try {
    const response = await fetcher("/account/saved-passengers", { cache: "no-store", credentials: "same-origin" });
    if (!response.ok) return [];
    const body: unknown = await response.json();
    const list = typeof body === "object" && body !== null ? (body as { passengers?: unknown }).passengers : undefined;
    if (!Array.isArray(list)) return [];
    return list
      .filter((entry): entry is SavedName => isName(entry))
      .map((entry) => ({ firstName: entry.firstName, lastName: entry.lastName }))
      .slice(0, MAX);
  } catch {
    return [];
  }
}

function isName(value: unknown): value is SavedName {
  if (typeof value !== "object" || value === null) return false;
  const { firstName, lastName } = value as Record<string, unknown>;
  return typeof firstName === "string" && firstName.length > 0 && typeof lastName === "string" && lastName.length > 0;
}
