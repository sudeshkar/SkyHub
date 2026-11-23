import type { components } from "./assistant-contract";

export type AssistantAnswer = components["schemas"]["Answer"];
export type AssistantTurn = components["schemas"]["Turn"];

export type AssistantOutcome =
  | { kind: "ok"; answer: AssistantAnswer }
  | { kind: "busy"; retryAfterSeconds: number } // busy with other travellers, or the model is down (503)
  | { kind: "rejected"; detail: string } // it answered, and said what was wrong with the message (400)
  | { kind: "unavailable" }; // not reachable, not configured, or a setup problem (a wrong token)

/** The booking the traveller has open on the Manage page (ADR-0094). */
export type OpenBooking = { locator: string; token: string };

// Server-side only: the browser never calls the assistant, and the shared token never leaves the server (ADR-0093).
const DEFAULT_URL = "http://localhost:8085";
// A crew with a language model takes a few seconds; a rate-limited model can take about ten (ADR-0094).
const TIMEOUT_MS = 60_000;
const DEFAULT_RETRY_SECONDS = 5;

/** The chat is shown only when the website can reach the assistant (ADR-0095). */
export function assistantConfigured(): boolean {
  return Boolean(process.env.SKYHUB_ASSISTANT_API_TOKEN);
}

/**
 * Asks the traveller assistant. The open booking, if any, goes in two headers and never in the body, so it is never
 * part of anything the model reads (ADR-0094). Nothing about the conversation is kept, here or there.
 */
export async function askTravellerAssistant(
  message: string,
  history: AssistantTurn[],
  booking: OpenBooking | null,
): Promise<AssistantOutcome> {
  const token = process.env.SKYHUB_ASSISTANT_API_TOKEN;
  if (!token) return { kind: "unavailable" };

  const headers: Record<string, string> = { "Content-Type": "application/json", Authorization: `Bearer ${token}` };
  if (booking) {
    headers["X-Booking-Reference"] = booking.locator;
    headers["X-Booking-Token"] = booking.token;
  }

  let response: Response;
  try {
    response = await fetch(new URL("/api/v1/assistant/messages", process.env.SKYHUB_ASSISTANT_API_URL || DEFAULT_URL), {
      method: "POST",
      headers,
      body: JSON.stringify({ message, history }),
      cache: "no-store",
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
  } catch {
    return { kind: "unavailable" };
  }
  if (response.status === 200) return { kind: "ok", answer: (await response.json()) as AssistantAnswer };
  if (response.status === 503) {
    const seconds = Number(response.headers.get("Retry-After"));
    return { kind: "busy", retryAfterSeconds: Number.isFinite(seconds) && seconds > 0 ? seconds : DEFAULT_RETRY_SECONDS };
  }
  if (response.status === 400) {
    const problem = (await response.json().catch(() => null)) as { detail?: string } | null;
    return { kind: "rejected", detail: problem?.detail ?? "The assistant could not read that message." };
  }
  return { kind: "unavailable" }; // including 401: a wrong token is a setup problem, not something a traveller can fix
}
