"use server";

import { askTravellerAssistant, type AssistantTurn } from "@/lib/api/assistant";
import { readSession } from "@/lib/manage-session";
import { POLICIES, rateLimit } from "@/lib/rate-limit";

/** One earlier turn, as the chat keeps it in the page. */
export type ChatTurn = AssistantTurn;

/** What the browser gets: what the traveller sees, and never the manage token or the booking reference. */
export type ChatResult =
  | { kind: "answer"; reply: string; sources: string[]; usedBooking: boolean }
  | { kind: "error"; message: string };

// The contract's limits (api-specs/traveller-assistant-api.yaml).
const MAX_MESSAGE = 1000;
const MAX_TURNS = 6;
const MAX_TURN = 1000;

/**
 * Asks the traveller assistant (ADR-0095). Called from the Manage page, this request is posted to /manage and so
 * carries the manage cookie: the booking open there is sent beside the message (ADR-0094). From any other page it
 * carries none and no booking is read. Reachable by a direct POST, so it checks its caller's rate and its input itself.
 */
export async function askAssistant(message: string, history: ChatTurn[]): Promise<ChatResult> {
  const decision = await rateLimit(POLICIES.assistant);
  if (!decision.allowed) return { kind: "error", message: "You've sent a lot of messages. Please try again in a minute." };

  const text = typeof message === "string" ? message.trim() : "";
  if (text.length === 0 || text.length > MAX_MESSAGE) {
    return { kind: "error", message: "Write a message of up to 1,000 characters." };
  }

  const booking = await readSession();
  const outcome = await askTravellerAssistant(text, recentTurns(history), booking);
  switch (outcome.kind) {
    case "ok":
      return {
        kind: "answer",
        reply: outcome.answer.reply,
        sources: outcome.answer.sources.map((source) => source.title),
        usedBooking: booking !== null && outcome.answer.route === "booking",
      };
    case "busy":
      return { kind: "error", message: "The assistant is busy with other travellers. Please try again in a moment." };
    case "rejected":
      return { kind: "error", message: outcome.detail };
    case "unavailable":
      return { kind: "error", message: "The assistant isn't available right now. Please try again later." };
  }
}

/** The last turns the assistant accepts, each cut to its limit; anything a direct POST could send that it would refuse is dropped. */
function recentTurns(history: unknown): ChatTurn[] {
  if (!Array.isArray(history)) return [];
  return history
    .filter(
      (turn): turn is ChatTurn =>
        typeof turn === "object" &&
        turn !== null &&
        ((turn as ChatTurn).role === "traveller" || (turn as ChatTurn).role === "assistant") &&
        typeof (turn as ChatTurn).text === "string" &&
        (turn as ChatTurn).text.trim().length > 0,
    )
    .slice(-MAX_TURNS)
    .map((turn) => ({ role: turn.role, text: turn.text.trim().slice(0, MAX_TURN) }));
}
