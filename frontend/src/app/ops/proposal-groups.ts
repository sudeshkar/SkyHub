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

/**
 * Groups proposals by destination flight and date, largest group first (then by flight and date, so the page does not shuffle), with
 * the bookings that need a person in a last group of their own. A non-empty `query` keeps only bookings whose reference contains it,
 * ignoring case and surrounding spaces, and drops groups left empty; passengers are counted from the bookings that remain. Bookings
 * keep their order within a group. One pass over the list plus a sort of the groups: O(n + g log g).
 */
export function groupProposals(proposals: readonly Proposal[], query: string): ProposalGroup[] {
  const needle = query.trim().toLowerCase();
  const groups = new Map<string, ProposalGroup>();
  let person: ProposalGroup | undefined;
  for (const proposal of proposals) {
    if (needle && !proposal.locator.toLowerCase().includes(needle)) continue;
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
