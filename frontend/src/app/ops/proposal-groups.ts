import type { Proposal } from "@/lib/api/agent";

/**
 * Bookings that are going to the same place (ADR-0043). `kind` "rebook" has a destination flight and date; "person" is the group of
 * bookings that need a person to decide, which has none.
 */
export type ProposalGroup = {
  key: string;
  kind: "rebook" | "person";
  flightNumber?: string;
  departureDate?: string;
  proposals: Proposal[];
  passengers: number;
};

/** How many rows are drawn at first, and added each time more are asked for (ADR-0068). */
export const ROW_WINDOW = 100;

/** One group and the rows of it that are drawn. The group keeps its true size and number of people. */
export type WindowedSection = { group: ProposalGroup; rows: Proposal[] };

export type WindowedGroups = { sections: WindowedSection[]; drawn: number; total: number };

/**
 * Cuts a long list down to the rows that are drawn (ADR-0068), in the order the groups are listed: the first `limit` bookings across the
 * groups, never fewer than one. Every group is kept, even one with no row drawn, so its heading and its buttons are still there and say
 * how big it really is. This only decides what is drawn, never what is selected or moved. One pass: O(n).
 */
export function windowGroups(groups: readonly ProposalGroup[], limit: number): WindowedGroups {
  let left = Math.max(1, limit);
  let drawn = 0;
  let total = 0;
  const sections = groups.map((group) => {
    const rows = group.proposals.slice(0, left);
    left -= rows.length;
    drawn += rows.length;
    total += group.proposals.length;
    return { group, rows };
  });
  return { sections, drawn, total };
}

/** Which bookings to show by what happened to them (ADR-0067). Each booking is in exactly one of the last three, or needs a person. */
export type OutcomeFilter = "all" | "todo" | "moved" | "problem";

/** The choices in the order they are offered. Adding an outcome is adding a line here and a case in {@link outcomeOf}. */
export const OUTCOME_OPTIONS: readonly { value: OutcomeFilter; label: string }[] = [
  { value: "all", label: "All" },
  { value: "todo", label: "Not tried yet" },
  { value: "moved", label: "Moved" },
  { value: "problem", label: "Refused or failed" },
];

/** What came back for each booking so far; only the status is needed here. */
export type OutcomeResults = Readonly<Record<string, { status: string }>>;

/** Where one booking stands. A booking that needs a person has no move to try, so it is none of the three outcomes. */
function outcomeOf(proposal: Proposal, results: OutcomeResults): Exclude<OutcomeFilter, "all"> | "person" {
  if (proposal.action !== "REBOOK") return "person";
  switch (results[proposal.locator]?.status) {
    case "moved":
    case "unchanged": // already on that flight counts as done
      return "moved";
    case "refused":
    case "failed":
      return "problem";
    default:
      return "todo";
  }
}

/** How many bookings are in each outcome, in one pass. {@code all} counts every booking, those that need a person included. */
export function outcomeCounts(proposals: readonly Proposal[], results: OutcomeResults): Record<OutcomeFilter, number> {
  const counts: Record<OutcomeFilter, number> = { all: 0, todo: 0, moved: 0, problem: 0 };
  for (const proposal of proposals) {
    counts.all++;
    const outcome = outcomeOf(proposal, results);
    if (outcome !== "person") counts[outcome]++;
  }
  return counts;
}

/**
 * Groups proposals by destination flight and date, largest group first (then by flight and date, so the page does not shuffle), with
 * the bookings that need a person in a last group of their own. A non-empty `query` keeps only bookings whose reference contains it,
 * ignoring case and surrounding spaces. An `outcome` other than "all" keeps only the bookings in that outcome (so the bookings that need
 * a person are shown under "all" alone). Groups left empty are dropped, and passengers are counted from the bookings that remain.
 * Bookings keep their order within a group. One pass over the list plus a sort of the groups: O(n + g log g).
 */
export function groupProposals(proposals: readonly Proposal[], query: string, outcome: OutcomeFilter = "all",
                               results: OutcomeResults = {}): ProposalGroup[] {
  const needle = query.trim().toLowerCase();
  const groups = new Map<string, ProposalGroup>();
  let person: ProposalGroup | undefined;
  for (const proposal of proposals) {
    if (needle && !proposal.locator.toLowerCase().includes(needle)) continue;
    if (outcome !== "all" && outcomeOf(proposal, results) !== outcome) continue;
    const passengers = proposal.passengerCount ?? 0;
    if (proposal.action !== "REBOOK" || !proposal.flightNumber || !proposal.departureDate) {
      person ??= { key: "person", kind: "person", proposals: [], passengers: 0 };
      person.proposals.push(proposal);
      person.passengers += passengers;
      continue;
    }
    const key = `${proposal.flightNumber}@${proposal.departureDate}`;
    let group = groups.get(key);
    if (!group) {
      group = { key, kind: "rebook", flightNumber: proposal.flightNumber, departureDate: proposal.departureDate, proposals: [], passengers: 0 };
      groups.set(key, group);
    }
    group.proposals.push(proposal);
    group.passengers += passengers;
  }
  const ordered = [...groups.values()].sort((a, b) => b.proposals.length - a.proposals.length || a.key.localeCompare(b.key));
  return person ? [...ordered, person] : ordered;
}
