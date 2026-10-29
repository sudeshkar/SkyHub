"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useRef, useState, useTransition } from "react";
import { createBooking } from "@/app/book/actions";
import type { Pnr, SeatMap as SeatMapData, Segment } from "@/lib/api/booking";
import { SeatMap } from "./seat-map";

type Step = "seats" | "passengers" | "review" | "done";
type Passenger = { firstName: string; lastName: string };

const STEPS: { id: Exclude<Step, "done">; label: string }[] = [
  { id: "seats", label: "Seats" },
  { id: "passengers", label: "Passengers" },
  { id: "review", label: "Review" },
];

function initials(passenger: Passenger, index: number): string {
  const letters = `${passenger.firstName.charAt(0)}${passenger.lastName.charAt(0)}`.toUpperCase();
  return letters || `P${index + 1}`;
}

export function BookingWizard({
  segments,
  seatMaps,
  passengerCount,
}: {
  segments: Segment[];
  seatMaps: SeatMapData[];
  passengerCount: number;
}) {
  const router = useRouter();
  const [step, setStep] = useState<Step>("seats");
  const [segmentIndex, setSegmentIndex] = useState(0);
  const [passengerIndex, setPassengerIndex] = useState(0);
  // seats[passenger][segment]
  const [seats, setSeats] = useState<string[][]>(() =>
    Array.from({ length: passengerCount }, () => Array<string>(segments.length).fill("")),
  );
  const [passengers, setPassengers] = useState<Passenger[]>(() =>
    Array.from({ length: passengerCount }, () => ({ firstName: "", lastName: "" })),
  );
  const [email, setEmail] = useState("");
  const [notice, setNotice] = useState<string | null>(null);
  const [booked, setBooked] = useState<Pnr | null>(null);
  const [pending, startTransition] = useTransition();
  // One key per review attempt: a double-click or retry replays instead of booking twice.
  const idempotencyKey = useRef<string>("");

  const allSeated = seats.every((row) => row.every(Boolean));
  const heldByParty = new Map<string, string>();
  seats.forEach((row, p) => {
    if (p !== passengerIndex && row[segmentIndex]) heldByParty.set(row[segmentIndex], initials(passengers[p], p));
  });

  function chooseSeat(seatNumber: string) {
    setSeats((current) => {
      const next = current.map((row) => [...row]);
      next[passengerIndex][segmentIndex] = next[passengerIndex][segmentIndex] === seatNumber ? "" : seatNumber;
      return next;
    });
    if (passengerIndex < passengerCount - 1) setPassengerIndex(passengerIndex + 1);
  }

  function goToReview(event: React.FormEvent) {
    event.preventDefault();
    idempotencyKey.current = crypto.randomUUID();
    setNotice(null);
    setStep("review");
  }

  function confirm() {
    startTransition(async () => {
      const outcome = await createBooking(
        {
          contactEmail: email,
          segments,
          passengers: passengers.map((p, i) => ({ ...p, seats: seats[i] })),
        },
        idempotencyKey.current,
      );
      if (outcome.kind === "booked") {
        setBooked(outcome.pnr);
        setStep("done");
      } else if (outcome.kind === "seat-taken") {
        setSeats(seats.map((row) => row.map(() => "")));
        setNotice("One of your seats was just taken by another traveller. Please choose your seats again.");
        setStep("seats");
        router.refresh();
      } else if (outcome.kind === "rate-limited") {
        setNotice(`Too many booking attempts. Please wait ${outcome.retryAfterSeconds} seconds and try again.`);
      } else if (outcome.kind === "rejected") {
        setNotice(outcome.detail);
      } else {
        setNotice("We couldn't reach the booking service, so nothing was booked. Please try again.");
      }
    });
  }

  if (step === "done" && booked) {
    return <Confirmation pnr={booked} />;
  }

  const seatMap = seatMaps[segmentIndex];

  return (
    <div>
      <ol className="mb-8 flex gap-6 text-sm" aria-label="Booking steps">
        {STEPS.map((s, i) => (
          <li
            key={s.id}
            aria-current={s.id === step ? "step" : undefined}
            className={s.id === step ? "font-semibold text-ink" : "text-muted"}
          >
            {i + 1}. {s.label}
          </li>
        ))}
      </ol>

      {notice && (
        <p role="alert" className="mb-6 border-l-4 border-danger bg-white px-4 py-3 text-sm text-danger">
          {notice}
        </p>
      )}

      {step === "seats" && (
        <section aria-labelledby="seats-heading" className="grid gap-8 lg:grid-cols-[1fr_18rem]">
          <div>
            <h2 id="seats-heading" className="sr-only">
              Choose seats
            </h2>
            {segments.length > 1 && (
              <div role="tablist" aria-label="Flights" className="mb-6 flex gap-2">
                {segments.map((segment, i) => (
                  <button
                    key={segment.flightNumber}
                    role="tab"
                    aria-selected={i === segmentIndex}
                    onClick={() => {
                      setSegmentIndex(i);
                      setPassengerIndex(0);
                    }}
                    className={`rounded-md border px-4 py-2 font-mono text-sm ${
                      i === segmentIndex ? "border-ink bg-ink text-ivory" : "border-sand bg-white"
                    }`}
                  >
                    {segment.flightNumber}
                  </button>
                ))}
              </div>
            )}
            <SeatMap
              seats={seatMap.seats}
              selected={seats[passengerIndex][segmentIndex] || undefined}
              heldByParty={heldByParty}
              onSelect={chooseSeat}
            />
          </div>

          <aside className="space-y-4">
            <h3 className="font-semibold">Passengers on {segments[segmentIndex].flightNumber}</h3>
            <ul className="space-y-2">
              {seats.map((row, p) => (
                <li key={p}>
                  <button
                    type="button"
                    onClick={() => setPassengerIndex(p)}
                    aria-pressed={p === passengerIndex}
                    className={`flex w-full justify-between rounded-md border px-3 py-2 text-left text-sm ${
                      p === passengerIndex ? "border-copper" : "border-sand"
                    }`}
                  >
                    <span>Passenger {p + 1}</span>
                    <span className="font-mono">{row[segmentIndex] || "—"}</span>
                  </button>
                </li>
              ))}
            </ul>
            <button
              type="button"
              disabled={!allSeated}
              onClick={() => setStep("passengers")}
              className="w-full rounded-md bg-copper px-4 py-3 font-semibold text-white hover:bg-copper-strong disabled:cursor-not-allowed disabled:opacity-50"
            >
              Continue to passengers
            </button>
            {!allSeated && <p className="text-xs text-muted">Choose a seat for every passenger on every flight.</p>}
          </aside>
        </section>
      )}

      {step === "passengers" && (
        <form onSubmit={goToReview} className="max-w-xl space-y-6">
          {passengers.map((passenger, p) => (
            <fieldset key={p} className="rounded-lg border border-sand bg-white p-5">
              <legend className="px-1 font-semibold">Passenger {p + 1}</legend>
              <p className="mb-3 text-sm text-muted">Enter names exactly as they appear on the passport.</p>
              <div className="grid gap-4 sm:grid-cols-2">
                {(["firstName", "lastName"] as const).map((field) => (
                  <label key={field} className="block text-sm font-medium">
                    {field === "firstName" ? "First name" : "Last name"}
                    <input
                      required
                      maxLength={50}
                      autoComplete={field === "firstName" ? "given-name" : "family-name"}
                      value={passenger[field]}
                      onChange={(e) =>
                        setPassengers(passengers.map((x, i) => (i === p ? { ...x, [field]: e.target.value } : x)))
                      }
                      className="mt-1 block w-full rounded-md border border-sand px-3 py-2"
                    />
                  </label>
                ))}
              </div>
            </fieldset>
          ))}
          <label className="block text-sm font-medium">
            Contact email
            <input
              required
              type="email"
              maxLength={254}
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="mt-1 block w-full rounded-md border border-sand px-3 py-2"
            />
            <span className="mt-1 block text-xs text-muted">We send the booking confirmation here.</span>
          </label>
          <div className="flex gap-4">
            <button type="button" onClick={() => setStep("seats")} className="rounded-md border border-sand px-5 py-3">
              Back
            </button>
            <button type="submit" className="rounded-md bg-copper px-6 py-3 font-semibold text-white hover:bg-copper-strong">
              Review booking
            </button>
          </div>
        </form>
      )}

      {step === "review" && (
        <section aria-labelledby="review-heading" className="max-w-xl space-y-6">
          <h2 id="review-heading" className="text-xl font-semibold">
            Check your booking
          </h2>
          <dl className="divide-y divide-sand rounded-lg border border-sand bg-white">
            {passengers.map((p, i) => (
              <div key={i} className="flex justify-between px-5 py-3">
                <dt>
                  {p.firstName} {p.lastName}
                </dt>
                <dd className="font-mono text-sm">
                  {segments.map((s, j) => `${s.flightNumber} ${seats[i][j]}`).join(" · ")}
                </dd>
              </div>
            ))}
            <div className="flex justify-between px-5 py-3">
              <dt>Confirmation to</dt>
              <dd>{email}</dd>
            </div>
          </dl>
          <div className="flex gap-4">
            <button
              type="button"
              disabled={pending}
              onClick={() => setStep("passengers")}
              className="rounded-md border border-sand px-5 py-3"
            >
              Back
            </button>
            <button
              type="button"
              disabled={pending}
              onClick={confirm}
              className="rounded-md bg-copper px-6 py-3 font-semibold text-white hover:bg-copper-strong disabled:opacity-60"
            >
              {pending ? "Booking…" : "Confirm booking"}
            </button>
          </div>
        </section>
      )}
    </div>
  );
}

function Confirmation({ pnr }: { pnr: Pnr }) {
  return (
    <section aria-labelledby="done-heading" className="max-w-xl rounded-lg border border-sand bg-white p-8">
      <p className="text-sm font-semibold text-copper">Booking confirmed</p>
      <h2 id="done-heading" className="mt-1 font-display text-3xl">
        Your booking reference
      </h2>
      <p className="mt-4 font-mono text-4xl tracking-[0.3em]" aria-label={`Booking reference ${pnr.locator.split("").join(" ")}`}>
        {pnr.locator}
      </p>
      <p className="mt-4 text-muted">
        Confirmation sent to {pnr.contactEmail}. Keep this reference and a passenger&apos;s last name to manage the
        booking.
      </p>
      <ul className="mt-6 space-y-1 text-sm">
        {pnr.passengers.map((p) => (
          <li key={`${p.firstName}-${p.lastName}-${p.seats.join()}`}>
            {p.firstName} {p.lastName} — <span className="font-mono">{p.seats.join(", ")}</span>
          </li>
        ))}
      </ul>
      <Link href="/manage" className="mt-8 inline-block text-copper hover:text-copper-strong">
        Manage this booking
      </Link>
    </section>
  );
}
