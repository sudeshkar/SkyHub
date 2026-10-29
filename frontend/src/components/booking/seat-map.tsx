"use client";

import type { Seat } from "@/lib/api/booking";

type Props = {
  seats: Seat[];
  /** Seat chosen by the passenger being seated now. */
  selected?: string;
  /** Seats already given to other passengers in this booking, with a short label (e.g. initials). */
  heldByParty: Map<string, string>;
  onSelect: (seatNumber: string) => void;
};

type Row = { row: number; cabin: Seat["cabin"]; seats: Seat[] };

function groupByRow(seats: Seat[]): Row[] {
  const rows = new Map<number, Row>();
  for (const seat of seats) {
    const row = rows.get(seat.row) ?? { row: seat.row, cabin: seat.cabin, seats: [] };
    row.seats.push(seat);
    rows.set(seat.row, row);
  }
  return [...rows.values()].sort((a, b) => a.row - b.row);
}

/** Aisle position: after C in both cabins (business A C | D F, economy A B C | D E F). */
const AISLE_AFTER = "C";

export function SeatMap({ seats, selected, heldByParty, onSelect }: Props) {
  const rows = groupByRow(seats);

  return (
    <div className="mx-auto w-fit rounded-t-[4rem] border border-sand bg-white px-6 pb-6 pt-10">
      {rows.map((row, index) => {
        const cabinStarts = index === 0 || rows[index - 1].cabin !== row.cabin;
        return (
          <div key={row.row}>
            {cabinStarts && (
              <p className="mb-2 mt-4 text-center text-xs font-semibold tracking-wide text-muted first:mt-0">
                {row.cabin === "BUSINESS" ? "Business" : "Economy"}
              </p>
            )}
            <div className="mb-1.5 flex items-center justify-center gap-1.5">
              <span className="w-6 text-right font-mono text-xs text-muted" aria-hidden="true">
                {row.row}
              </span>
              {row.seats.map((seat) => {
                const heldBy = heldByParty.get(seat.seatNumber);
                const isSelected = selected === seat.seatNumber;
                const taken = seat.status === "BOOKED";
                const heldElsewhere = seat.status === "HELD"; // someone else is checking out with it
                const unavailable = taken || heldElsewhere || heldBy !== undefined;
                const cabin = seat.cabin === "BUSINESS" ? "business" : "economy";
                // A seat held by someone in this booking takes priority over "taken": when managing a booking, the
                // party's own seats are already booked, and they should read as the party's, not as a stranger's.
                const state = isSelected ? "selected" : heldBy
                  ? "chosen for another passenger"
                  : heldElsewhere
                    ? "held by another traveller"
                    : taken
                      ? "taken"
                      : "available";
                return (
                  <span key={seat.seatNumber} className="flex items-center">
                    <button
                      type="button"
                      disabled={unavailable && !isSelected}
                      aria-pressed={isSelected}
                      aria-label={`Seat ${seat.seatNumber}, ${cabin}, ${state}`}
                      onClick={() => onSelect(seat.seatNumber)}
                      className={[
                        "flex items-center justify-center rounded-md border text-xs font-medium",
                        seat.cabin === "BUSINESS" ? "size-11" : "size-9",
                        isSelected
                          ? "border-copper bg-copper text-white"
                          : heldBy
                            ? "cursor-not-allowed border-ink-soft bg-ink-soft text-ivory"
                            : heldElsewhere
                              ? "cursor-not-allowed border-sand bg-sand-soft text-muted"
                              : taken
                                ? "cursor-not-allowed border-sand bg-sand-soft text-muted line-through"
                                : "border-sand bg-ivory hover:border-copper",
                      ].join(" ")}
                    >
                      {isSelected ? seat.letter : heldBy ?? (taken ? "×" : heldElsewhere ? "·" : seat.letter)}
                    </button>
                    {seat.letter === AISLE_AFTER && <span className="w-5" aria-hidden="true" />}
                  </span>
                );
              })}
            </div>
          </div>
        );
      })}
    </div>
  );
}
