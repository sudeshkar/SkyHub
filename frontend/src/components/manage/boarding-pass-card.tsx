import type { BoardingPass } from "@/lib/api/booking";

/** One passenger's boarding pass. The barcode text is what a gate scanner reads; it is signed, so it cannot be edited. */
export function BoardingPassCard({ pass }: { pass: BoardingPass }) {
  return (
    <li className="rounded-lg border border-ink bg-white p-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <p className="font-semibold">{pass.passengerName}</p>
        <p className="text-sm font-semibold text-copper">Boarding no. {pass.sequence}</p>
      </div>
      <p className="mt-1 font-mono text-sm">
        {pass.flightNumber} · {pass.departureDate} · Seat {pass.seatNumber}
      </p>
      <p className="mt-3 break-all rounded bg-sand-soft px-2 py-1.5 font-mono text-[11px] leading-snug text-muted">
        {pass.barcode}
      </p>
    </li>
  );
}
