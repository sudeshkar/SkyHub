import type { components } from "./agent-contract";

export type ProposalSet = components["schemas"]["ProposalSet"];
export type Proposal = components["schemas"]["Proposal"];

export type ProposalsOutcome =
  | { kind: "ok"; set: ProposalSet }
  | { kind: "busy" } // the agent is already working on other requests
  | { kind: "rejected"; detail: string } // it answered, and said why it cannot help (for example a service is down)
  | { kind: "unavailable" }; // not reachable, or not configured

// Server-side only: the browser never calls the agent, and the shared token never leaves the server (ADR-0024).
const API_URL = process.env.SKYHUB_AGENT_API_URL ?? "http://localhost:8084";
// The rule-based planner answers in milliseconds; a language model can take far longer.
const TIMEOUT_MS = 60_000;

/** Asks the agent what to do with every active booking on a disrupted flight. It only proposes: nothing is changed. */
export async function getProposals(flightNumber: string, departureDate: string): Promise<ProposalsOutcome> {
  const token = process.env.SKYHUB_AGENT_API_TOKEN;
  if (!token) return { kind: "unavailable" };

  let response: Response;
  try {
    response = await fetch(new URL("/v1/proposals", API_URL), {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
      body: JSON.stringify({ flightNumber, departureDate }),
      cache: "no-store",
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
  } catch {
    return { kind: "unavailable" };
  }
  if (response.status === 200) return { kind: "ok", set: (await response.json()) as ProposalSet };
  if (response.status === 503) return { kind: "busy" };
  if (response.status === 400 || response.status === 502) {
    const problem = (await response.json().catch(() => null)) as { detail?: string } | null;
    return { kind: "rejected", detail: problem?.detail ?? "The agent could not make proposals." };
  }
  return { kind: "unavailable" }; // including 401: a wrong token is a setup problem, not something staff can fix
}
