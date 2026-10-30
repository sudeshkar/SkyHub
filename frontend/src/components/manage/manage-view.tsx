"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import {
  cancelBooking,
  changeSeat,
  checkInFlight,
  closeSession,
  loadSeatMap,
  type CheckInActionResult,
  type ManageActionResult,
} from "@/app/manage/actions";
import type { BoardingPass, Disruption, Pnr, SeatMap as SeatMapData } from "@/lib/api/booking";
import { formatPrice, utcDateTime } from "@/lib/format";
import { SeatMap } from "@/components/booking/seat-map";
import { BoardingPassCard } from "./boarding-pass-card";

type Editing = { passenger: number; segment: number; map: SeatMapData | null; choice: string };
type Notice = { tone: "ok" | "error"; text: string };

function initials(firstName: string, lastName: string): string {
  return `${firstName.charAt(0)}${lastName.charAt(0)}`.toUpperCase();
}

/** Plain-language message for anything other than success. */
function failureText(result: Exclude<ManageActionResult, { kind: "ok" }>): string {
  switch (result.kind) {
    case "unauthorized":
      return "Your session has ended. Enter your booking details again to continue.";
    case "seat-taken":
    case "cancelled":
    case "rejected":
      return result.detail;
    case "rate-limited":
      return `Too many requests. Please wait ${result.retryAfterSeconds} seconds and try again.`;
    case "not-found":
      return "We couldn't find that booking any more.";
    default:
      return "We couldn't reach the booking service. Nothing was changed. Please try again.";
  }
}

/** Plain-language message when check-in did not happen. */
function checkInFailure(result: Exclude<CheckInActionResult, { kind: "ok" }>): string {
  switch (result.kind) {
    case "closed":
    case "cancelled":
    case "rejected":
      return result.detail;
    case "unauthorized":
      return "Your session has ended. Enter your booking details again to continue.";
    case "rate-limited":
      return `Too many requests. Please wait ${result.retryAfterSeconds} seconds and try again.`;
    case "not-found":
      return "We couldn't find that booking any more.";
    default:
      return "We couldn't reach the booking service, so you are not checked in yet. Please try again.";
  }
}

/** `boardingPasses` maps a flight's position in the booking to its passes, for flights already checked in. */
export function ManageView({
  pnr,
  boardingPasses = {},
  disruptions = [],
}: {
  pnr: Pnr;
  boardingPasses?: Record<number, BoardingPass[]>;
  /** Delays and cancellations on this booking's flights; `segmentIndex` is the flight's position in the booking. */
  disruptions?: Disruption[];
}) {
  const router = useRouter();
  const [passes, setPasses] = useState<Record<number, BoardingPass[]>>(boardingPasses);
  const [editing, setEditing] = useState<Editing | null>(null);
  const [confirmingCancel, setConfirmingCancel] = useState(false);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [pending, startTransition] = useTransition();
  const cancelled = pnr.status === "CANCELLED";

  function report(result: ManageActionResult, successText: string) {
    if (result.kind === "ok") {
      setNotice({ tone: "ok", text: successText });
      router.refresh();
      return true;
    }
    setNotice({ tone: "error", text: failureText(result) });
    if (result.kind === "unauthorized") router.refresh(); // the page now shows the lookup form
    return false;
  }

  function checkIn(segment: number) {
    setNotice(null);
    startTransition(async () => {
      const result = await checkInFlight(segment);
      if (result.kind === "ok") {
        setPasses((current) => ({ ...current, [segment]: result.passes }));
        setEditing((current) => (current?.segment === segment ? null : current));
        setNotice({ tone: "ok", text: `Checked in for ${pnr.segments[segment].flightNumber}.` });
        return;
      }
      setNotice({ tone: "error", text: checkInFailure(result) });
      if (result.kind === "unauthorized") router.refresh(); // the page now shows the lookup form
    });
  }

  function openPicker(passenger: number, segment: number) {
    setNotice(null);
    setEditing({ passenger, segment, map: null, choice: pnr.passengers[passenger].seats[segment] });
    startTransition(async () => {
      const map = await loadSeatMap(pnr.segments[segment]);
      setEditing((current) => (current ? { ...current, map } : current));
      if (!map) setNotice({ tone: "error", text: "We couldn't load the seat map. Please try again." });
    });
  }

  function confirmSeat() {
    if (!editing) return;
    const { passenger, segment, choice } = editing;
    startTransition(async () => {
      const result = await changeSeat({ passengerIndex: passenger, segmentIndex: segment, seatNumber: choice });
      if (report(result, `Seat changed to ${choice}.`)) {
        setEditing(null);
      } else if (result.kind === "seat-taken") {
        // Someone else took it first: show the fresh map so the next pick is accurate.
        const map = await loadSeatMap(pnr.segments[segment]);
        setEditing((current) => (current ? { ...current, map, choice: pnr.passengers[passenger].seats[segment] } : current));
      }
    });
  }

  function confirmCancel() {
    startTransition(async () => {
      const result = await cancelBooking();
      if (report(result, "Your booking is cancelled and your seats are released.")) {
        setConfirmingCancel(false);
        setEditing(null);
      }
    });
  }

  const picking = editing && editing.map ? editing : null;
  const othersHeld = new Map<string, string>();
  if (picking) {
    pnr.passengers.forEach((p, index) => {
      if (index !== picking.passenger) othersHeld.set(p.seats[picking.segment], initials(p.firstName, p.lastName));
    });
  }

  return (
    <div className="mt-8 space-y-8">
      <header className="flex flex-wrap items-start justify-between gap-4 border-b border-sand pb-6">
        <div>
          <p className="text-sm text-muted">Booking reference</p>
          <p
            className="font-mono text-3xl tracking-[0.25em]"
            aria-label={`Booking reference ${pnr.locator.split("").join(" ")}`}
          >
            {pnr.locator}
          </p>
          <p className={`mt-2 text-sm font-semibold ${cancelled ? "text-danger" : "text-copper"}`}>
            {cancelled ? "Cancelled" : "Confirmed"}
          </p>
          <p className="mt-1 text-sm text-muted">Confirmation sent to {pnr.contactEmail}</p>
        </div>
        <form action={closeSession}>
          <button type="submit" className="rounded-md border border-sand px-4 py-2 text-sm hover:bg-sand-soft">
            Close this booking
          </button>
        </form>
      </header>

      {cancelled && pnr.refund && (
        <RefundNotice
          refund={pnr.refund}
          pending={pending}
          onRetry={() => startTransition(async () => void report(await cancelBooking(), "Asked the payment provider again."))}
        />
      )}

      {notice && (
        <p
          role={notice.tone === "error" ? "alert" : "status"}
          className={`border-l-4 bg-white px-4 py-3 text-sm ${
            notice.tone === "error" ? "border-danger text-danger" : "border-copper text-text"
          }`}
        >
          {notice.text}
        </p>
      )}

      <section aria-label="Flights and seats" className="space-y-8">
        {pnr.segments.map((segment, s) => {
          const disruption = disruptions.find((d) => d.segmentIndex === s);
          const flightCancelled = disruption?.type === "CANCELLED";
          return (
            <div key={`${segment.flightNumber}-${segment.departureDate}`}>
              <div className="flex flex-wrap items-center justify-between gap-3">
                <h2 className="font-mono text-lg">
                  {segment.flightNumber} <span className="text-muted">· {segment.departureDate}</span>
                </h2>
                {passes[s] ? (
                  <p className="text-sm font-semibold text-copper">Checked in</p>
                ) : (
                  !cancelled &&
                  !flightCancelled && (
                    <button
                      type="button"
                      disabled={pending}
                      onClick={() => checkIn(s)}
                      aria-label={`Check in for ${segment.flightNumber}`}
                      className="rounded-md border border-copper px-4 py-1.5 text-sm font-semibold text-copper hover:bg-sand-soft disabled:opacity-50"
                    >
                      Check in
                    </button>
                  )
                )}
              </div>
              {disruption && <DisruptionNotice flightNumber={segment.flightNumber} disruption={disruption} />}
              <ul className="mt-3 divide-y divide-sand rounded-lg border border-sand bg-white">
                {pnr.passengers.map((passenger, p) => (
                  <li key={`${passenger.firstName}-${passenger.lastName}-${p}`} className="flex items-center justify-between gap-4 px-5 py-3">
                    <span>
                      {passenger.firstName} {passenger.lastName}
                    </span>
                    <span className="flex items-center gap-4">
                      <span className="font-mono text-sm">Seat {passenger.seats[s]}</span>
                      <button
                        type="button"
                        disabled={cancelled || flightCancelled || pending || Boolean(passes[s])}
                        onClick={() => openPicker(p, s)}
                        aria-label={`Change seat for ${passenger.firstName} ${passenger.lastName} on ${segment.flightNumber}`}
                        className="text-sm text-copper hover:text-copper-strong disabled:cursor-not-allowed disabled:text-muted"
                      >
                        Change seat
                      </button>
                    </span>
                  </li>
                ))}
              </ul>
              {passes[s] && !cancelled && (
                <ul aria-label={`Boarding passes for ${segment.flightNumber}`} className="mt-3 grid gap-3 sm:grid-cols-2">
                  {passes[s].map((pass) => (
                    <BoardingPassCard key={pass.barcode} pass={pass} />
                  ))}
                </ul>
              )}
            </div>
          );
        })}
      </section>

      {editing && (
        <section
          aria-label="Choose a new seat"
          className="rounded-lg border border-copper bg-white p-6"
        >
          <h2 className="font-semibold">
            New seat for {pnr.passengers[editing.passenger].firstName} on {pnr.segments[editing.segment].flightNumber}
          </h2>
          {picking ? (
            <>
              <div className="mt-4">
                <SeatMap
                  seats={picking.map!.seats}
                  selected={picking.choice}
                  heldByParty={othersHeld}
                  onSelect={(seat) => setEditing({ ...picking, choice: seat })}
                />
              </div>
              <div className="mt-6 flex flex-wrap gap-4">
                <button
                  type="button"
                  disabled={pending || picking.choice === pnr.passengers[picking.passenger].seats[picking.segment]}
                  onClick={confirmSeat}
                  className="rounded-md bg-copper px-5 py-2.5 font-semibold text-white hover:bg-copper-strong disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {pending ? "Saving…" : `Move to seat ${picking.choice}`}
                </button>
                <button type="button" onClick={() => setEditing(null)} className="rounded-md border border-sand px-5 py-2.5">
                  Keep my seat
                </button>
              </div>
            </>
          ) : (
            <p aria-busy="true" className="mt-4 text-muted">
              Loading the seat map…
            </p>
          )}
        </section>
      )}

      {!cancelled && (
        <section aria-label="Cancel booking" className="border-t border-sand pt-6">
          {confirmingCancel ? (
            <div role="alertdialog" aria-labelledby="cancel-heading" className="max-w-lg rounded-lg border border-danger bg-white p-5">
              <h2 id="cancel-heading" className="font-semibold">
                Cancel this booking?
              </h2>
              <p className="mt-2 text-sm text-muted">
                Every seat on every flight is released and the booking cannot be restored. You would need to book again.
              </p>
              <p className="mt-2 text-sm text-muted">
                You get a full refund if you cancel 24 hours or more before the first flight, or if the airline cancelled a
                flight on this booking. Otherwise nothing is refunded.
              </p>
              <div className="mt-4 flex gap-3">
                <button
                  type="button"
                  disabled={pending}
                  onClick={confirmCancel}
                  className="rounded-md bg-danger px-5 py-2.5 font-semibold text-white disabled:opacity-60"
                >
                  {pending ? "Cancelling…" : "Yes, cancel booking"}
                </button>
                <button type="button" onClick={() => setConfirmingCancel(false)} className="rounded-md border border-sand px-5 py-2.5">
                  Keep booking
                </button>
              </div>
            </div>
          ) : (
            <button
              type="button"
              onClick={() => {
                setNotice(null);
                setConfirmingCancel(true);
              }}
              className="text-sm text-danger hover:underline"
            >
              Cancel booking
            </button>
          )}
        </section>
      )}
    </div>
  );
}

/** What became of the money after a cancellation (ADR-0016). A refund the provider has not confirmed can be retried. */
function RefundNotice({
  refund,
  pending,
  onRetry,
}: {
  refund: NonNullable<Pnr["refund"]>;
  pending: boolean;
  onRetry: () => void;
}) {
  const money = formatPrice(refund.amount, refund.currency);
  return (
    <section aria-label="Refund" className="rounded-lg border border-sand bg-white p-5">
      <h2 className="font-semibold">Refund</h2>
      {refund.status === "REFUNDED" && (
        <p className="mt-2 text-sm">{money} has been refunded to your original payment method.</p>
      )}
      {refund.status === "NOT_REFUNDABLE" && (
        <p className="mt-2 text-sm">
          This booking was cancelled less than 24 hours before departure, so the {money} you paid is not refundable.
        </p>
      )}
      {refund.status === "PENDING" && (
        <>
          <p className="mt-2 text-sm">
            Your {money} refund is on its way, but the payment provider has not confirmed it yet. You can ask again.
          </p>
          <button
            type="button"
            disabled={pending}
            onClick={onRetry}
            className="mt-3 rounded-md border border-sand px-4 py-2 text-sm font-semibold disabled:opacity-60"
          >
            Try the refund again
          </button>
        </>
      )}
    </section>
  );
}

/** A delay is information; a cancellation needs action, so it is announced as an alert with a way forward. */
function DisruptionNotice({ flightNumber, disruption }: { flightNumber: string; disruption: Disruption }) {
  if (disruption.type === "CANCELLED") {
    return (
      <div
        role="alert"
        aria-label={`${flightNumber} is cancelled`}
        className="mt-3 border-l-4 border-danger bg-white px-4 py-3 text-sm"
      >
        <p className="font-semibold text-danger">This flight is cancelled.</p>
        {disruption.reason && <p className="mt-1 text-muted">{disruption.reason}</p>}
        <p className="mt-2">
          You can cancel this booking below and{" "}
          <Link href="/" className="text-copper underline hover:text-copper-strong">
            Search for another flight
          </Link>
          .
        </p>
      </div>
    );
  }
  return (
    <p
      role="status"
      aria-label={`${flightNumber} is delayed`}
      className="mt-3 border-l-4 border-copper bg-white px-4 py-3 text-sm"
    >
      <span className="font-semibold">Delayed.</span> New departure{" "}
      {disruption.revisedDeparture ? utcDateTime(disruption.revisedDeparture) : "to be confirmed"}.
      {disruption.reason && <span className="text-muted"> {disruption.reason}</span>}
    </p>
  );
}
