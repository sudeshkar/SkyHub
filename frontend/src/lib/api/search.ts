import type { components } from "./flight-search";

export type Itinerary = components["schemas"]["Itinerary"];
export type FlightLeg = components["schemas"]["FlightLeg"];
export type Connection = components["schemas"]["Connection"];
type Problem = components["schemas"]["Problem"];

export type SearchQuery = { origin: string; destination: string; date: string };

export type SearchResult =
  | { kind: "ok"; itineraries: Itinerary[] }
  | { kind: "rejected"; detail?: string }
  | { kind: "unavailable" };

// Server-side only: the browser never calls the Java service directly.
const API_URL = process.env.SKYHUB_SEARCH_API_URL ?? "http://localhost:8081";

export async function searchItineraries(query: SearchQuery): Promise<SearchResult> {
  const url = new URL("/api/v1/itineraries", API_URL);
  url.searchParams.set("origin", query.origin);
  url.searchParams.set("destination", query.destination);
  url.searchParams.set("date", query.date);

  let response: Response;
  try {
    response = await fetch(url, { signal: AbortSignal.timeout(5_000) });
  } catch {
    return { kind: "unavailable" };
  }

  if (response.ok) {
    return { kind: "ok", itineraries: (await response.json()) as Itinerary[] };
  }
  if (response.status === 400) {
    const problem = (await response.json().catch(() => null)) as Problem | null;
    return { kind: "rejected", detail: problem?.detail };
  }
  return { kind: "unavailable" };
}
