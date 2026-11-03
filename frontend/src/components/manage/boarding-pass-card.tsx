import type { BoardingPass } from "@/lib/api/booking";
import { QrCode } from "./qr-code";

/**
 * One passenger's boarding pass. The barcode text is what a gate scanner reads; it is signed, so it cannot be edited. It is drawn as a QR
 * code (ADR-0071) and the text stays under it, so it can still be copied when a camera cannot be used.
 */
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
      <QrCode text={pass.barcode} label={`QR code of the boarding pass for ${pass.passengerName}`} className="mx-auto mt-3 size-44 max-w-full" />
      <p className="mt-3 break-all rounded bg-sand-soft px-2 py-1.5 font-mono text-[11px] leading-snug text-muted">
        {pass.barcode}
      </p>
    </li>
  );
}
