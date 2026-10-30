"use client";

import { useActionState, useState } from "react";
import type { Proposal, ProposalSet } from "@/lib/api/agent";
import { loadProposals, moveBookings, type MoveResult, type ProposalsState } from "./actions";
import { MAX_MOVES_PER_CALL as BATCH } from "./limits";

const field = "mt-1 block w-full rounded-md border border-sand bg-white px-3 py-2.5";

/**
 * Where staff review what the agent proposes after a disruption, and approve it (ADR-0024). Nothing moves until a
 * person ticks it and presses the button, and each move goes to the booking service with the staff member's own
 * login, so the audit names whoever approved.
 */
export function ProposalsPanel() {
  const [state, action, pending] = useActionState<ProposalsState, FormData>(loadProposals, { status: "idle" });
  const typed = state.status === "error" ? state.values : undefined;

  return (
    <section aria-label="Proposals" className="mt-12 space-y-6 border-t border-sand pt-10">
      <form action={action} className="max-w-md space-y-4">
        <h2 className="font-display text-xl">Move affected bookings</h2>
        <p className="text-sm text-muted">
          After recording a disruption, ask for proposals for everyone booked on that flight. You approve each move.
        </p>
        <label className="block text-sm font-medium">
          Disrupted flight
          <input
            name="flightNumber"
            defaultValue={typed?.flightNumber ?? ""}
            required
            maxLength={6}
            autoCapitalize="characters"
            autoComplete="off"
            placeholder="SK501"
            className={`${field} font-mono uppercase tracking-widest`}
          />
        </label>
        <label className="block text-sm font-medium">
          Scheduled departure date (UTC)
          <input name="departureDate" type="date" defaultValue={typed?.departureDate ?? ""} required className={field} />
        </label>
        <button
          type="submit"
          disabled={pending}
          className="rounded-md border border-copper px-6 py-3 font-semibold text-copper hover:bg-white disabled:opacity-60"
        >
          {pending ? "Asking…" : "Get proposals"}
        </button>
        {state.status === "error" && (
          <p role="alert" className="text-sm text-danger">
            {state.message}
          </p>
        )}
        {state.status === "signed-out" && (
          <p role="alert" className="text-sm text-danger">
            Your session has ended. <a href="/ops" className="underline">Sign in again</a>.
          </p>
        )}
      </form>

      {state.status === "loaded" && <ProposalTable key={signature(state.set)} set={state.set} />}
    </section>
  );
}

/** A new set of proposals starts a fresh table (nothing ticked off, no results), without effects or resets. */
function signature(set: ProposalSet): string {
  return `${set.flightNumber}|${set.departureDate}|${set.proposals.map((p) => `${p.locator}:${p.flightNumber}`).join(",")}`;
}

const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? "" : "s"}`;

function ProposalTable({ set }: { set: ProposalSet }) {
  const [deselected, setDeselected] = useState<ReadonlySet<string>>(new Set());
  const [results, setResults] = useState<Readonly<Record<string, MoveResult>>>({});
  const [progress, setProgress] = useState<{ done: number; total: number } | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [signedOut, setSignedOut] = useState(false);

  const done = (p: Proposal) => results[p.locator]?.status === "moved" || results[p.locator]?.status === "unchanged";
  const movable = set.proposals.filter((p) => p.action === "REBOOK" && !done(p));
  const chosen = movable.filter((p) => !deselected.has(p.locator));
  const moving = progress !== null;

  function toggle(locator: string) {
    setDeselected((current) => {
      const next = new Set(current);
      if (!next.delete(locator)) next.add(locator);
      return next;
    });
  }

  async function approve() {
    const moves = chosen.map((p) => ({
      locator: p.locator, segmentIndex: p.segmentIndex ?? 0, flightNumber: p.flightNumber ?? "", departureDate: p.departureDate ?? "",
    }));
    setProblem(null);
    setProgress({ done: 0, total: moves.length });
    for (let start = 0; start < moves.length; start += BATCH) {
      const outcome = await moveBookings(moves.slice(start, start + BATCH));
      if ("results" in outcome) {
        setResults((current) => ({ ...current, ...Object.fromEntries(outcome.results.map((r) => [r.locator, r])) }));
      }
      if (outcome.status === "signed-out") setSignedOut(true);
      if (outcome.status === "error") setProblem(outcome.message);
      if (outcome.status !== "done") break;
      setProgress({ done: Math.min(start + BATCH, moves.length), total: moves.length });
      if (outcome.results.length < Math.min(BATCH, moves.length - start)) break; // the booking service stopped answering
    }
    setProgress(null);
  }

  const counts = Object.values(results).reduce(
    (sum, r) => ({ ...sum, [r.status]: (sum[r.status] ?? 0) + 1 }),
    {} as Record<MoveResult["status"], number>,
  );

  if (set.proposals.length === 0) {
    return <p className="text-muted">No active bookings are on {set.flightNumber} on {set.departureDate}.</p>;
  }

  return (
    <div className="space-y-4">
      <p className="text-sm text-muted">
        {set.flightNumber} on {set.departureDate}: {plural(set.bookingsTotal, "active booking")}, {set.rebooked} proposed for a
        move. {set.planner === "first-fit" ? "Planned by the rule-based first-fit planner." : `Planned by the ${set.planner} planner.`}
      </p>

      {signedOut && (
        <p role="alert" className="border-l-4 border-amber-line bg-amber-bg px-4 py-3 text-sm">
          Your session has ended. <a href="/ops" className="underline">Sign in again</a> to carry on. Moves already made are kept.
        </p>
      )}
      {problem && (
        <p role="alert" className="text-sm text-danger">
          {problem}
        </p>
      )}

      <ul className="divide-y divide-sand border border-sand bg-white">
        {set.proposals.map((p) => (
          <ProposalRow key={p.locator} proposal={p} result={results[p.locator]} selected={!deselected.has(p.locator)}
            disabled={moving} onToggle={() => toggle(p.locator)} />
        ))}
      </ul>

      {movable.length > 0 && (
        <button
          type="button"
          onClick={approve}
          disabled={moving || chosen.length === 0}
          className="rounded-md bg-copper px-6 py-3 font-semibold text-white hover:bg-copper-strong disabled:opacity-60"
        >
          {moving ? `Moving ${progress.done} of ${progress.total}…` : `Move ${chosen.length} selected`}
        </button>
      )}

      {Object.keys(results).length > 0 && (
        <p role="status" className="text-sm">
          {counts.moved ?? 0} moved, {counts.unchanged ?? 0} already there, {counts.refused ?? 0} refused, {counts.failed ?? 0} failed.
        </p>
      )}
    </div>
  );
}

function ProposalRow({ proposal, result, selected, disabled, onToggle }: {
  proposal: Proposal; result: MoveResult | undefined; selected: boolean; disabled: boolean; onToggle: () => void;
}) {
  const rebook = proposal.action === "REBOOK";
  const finished = result?.status === "moved" || result?.status === "unchanged";
  return (
    <li className="flex flex-wrap items-start gap-3 px-4 py-3">
      {rebook && !finished ? (
        <input type="checkbox" aria-label={`Move ${proposal.locator}`} checked={selected} disabled={disabled} onChange={onToggle}
          className="mt-1.5 size-4" />
      ) : (
        <span aria-hidden className="mt-1.5 size-4" />
      )}
      <div className="min-w-0 flex-1">
        <p>
          <span className="font-mono font-semibold tracking-widest">{proposal.locator}</span>
          <span className="text-muted"> · {plural(proposal.passengerCount ?? 0, "passenger")}</span>
        </p>
        {rebook ? (
          <p className="text-sm">
            Move to <strong>{proposal.flightNumber} on {proposal.departureDate}</strong>. <span className="text-muted">{proposal.rationale}</span>
          </p>
        ) : (
          <p className="text-sm text-muted">Needs a person to decide. {proposal.rationale}</p>
        )}
        {result && <ResultLine result={result} />}
      </div>
    </li>
  );
}

function ResultLine({ result }: { result: MoveResult }) {
  if (result.status === "moved") return <p className="text-sm text-text">Moved · seats {result.seats?.join(", ")}</p>;
  if (result.status === "unchanged") return <p className="text-sm text-text">Already on that flight · seats {result.seats?.join(", ")}</p>;
  return <p className="text-sm text-danger">{result.status === "refused" ? "Refused" : "Not moved"}: {result.detail}</p>;
}
