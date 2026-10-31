"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useRef, useState, useTransition } from "react";
import { createBooking, holdSeats, quoteTrip, releaseSeats } from "@/app/book/actions";
import type { Pnr, QuoteOutcome, SeatMap as SeatMapData, Segment } from "@/lib/api/booking";
import { formatPrice } from "@/lib/format";
import { HoldCountdown } from "./hold-countdown";
import { DEFAULT_PAYMENT_TOKEN, PaymentSummary } from "./payment-summary";
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

type WizardProps = {
  segments: Segment[];
  seatMaps: SeatMapData[];
  passengerCount: number;
};

/**
 * The booking page is one page for every search, so a link, Back or Forward to a booking for other flights or another number of
 * passengers shows the same component with new props. What the traveller chose (seats, names, a hold) belongs to one booking and
 * is sized for it, so it starts afresh whenever the booking changes: keeping it crashed the page, with seats for one passenger and
 * a second one to choose for. A re-render of the same booking (new objects, same flights and count) keeps everything.
 */
export function BookingWizard(props: WizardProps) {
  const booking = `${props.passengerCount}:${props.segments.map((s) => `${s.flightNumber}_${s.departureDate}`).join(",")}`;
  return <Wizard key={booking} {...props} />;
}

function Wizard({ segments, seatMaps, passengerCount }: WizardProps) {
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
  const [booked, setBooked] = useState<{ pnr: Pnr; paid: string } | null>(null);
  // The price the server worked out for this trip; null while it is being worked out.
  const [quote, setQuote] = useState<QuoteOutcome | null>(null);
  const [paymentToken, setPaymentToken] = useState<string>(DEFAULT_PAYMENT_TOKEN);
  // Set while the chosen seats are held for this traveller, so nobody else can take them mid-checkout.
  const [hold, setHold] = useState<{ id: string; expiresAt: string } | null>(null);
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

  function continueToPassengers() {
    setNotice(null);
    startTransition(async () => {
      const outcome = await holdSeats(segments, seats);
      if (outcome.kind === "held") {
        setHold({ id: outcome.holdId, expiresAt: outcome.expiresAt });
        setStep("passengers");
      } else if (outcome.kind === "seat-taken") {
        setSeats(seats.map((row) => row.map(() => "")));
        setNotice("One of your seats was just taken by another traveller. Please choose your seats again.");
        router.refresh();
      } else if (outcome.kind === "rate-limited") {
        setNotice(`Too many attempts. Please wait ${outcome.retryAfterSeconds} seconds and try again.`);
      } else if (outcome.kind === "rejected") {
        setNotice(outcome.detail);
      } else {
        setNotice("We couldn't reach the booking service, so your seats are not held yet. Please try again.");
      }
    });
  }

  /** Going back to change seats lets go of the hold, so other travellers can see those seats again. */
  function backToSeats() {
    if (hold) void releaseSeats(hold.id);
    setHold(null);
    setStep("seats");
  }

  function holdEnded() {
    setHold(null);
    setNotice("Your seat hold ended. Check your seats and continue to hold them again.");
    setStep("seats");
    router.refresh(); // show the current seat map
  }

  function fetchQuote() {
    setQuote(null);
    startTransition(async () => {
      const result = await quoteTrip(segments, passengerCount);
      setQuote(result.kind === "quoted" ? result : { kind: "unavailable" });
    });
  }

  function goToReview(event: React.FormEvent) {
    event.preventDefault();
    idempotencyKey.current = crypto.randomUUID();
    setNotice(null);
    setStep("review");
    fetchQuote();
  }

  function confirm() {
    if (quote?.kind !== "quoted") return;
    const shown = quote;
    startTransition(async () => {
      const outcome = await createBooking(
        {
          paymentToken,
          expectedAmount: shown.amount,
          holdId: hold?.id,
          contactEmail: email,
          segments,
          passengers: passengers.map((p, i) => ({ ...p, seats: seats[i] })),
        },
        idempotencyKey.current,
      );
      if (outcome.kind === "booked") {
        setHold(null);
        setBooked({ pnr: outcome.pnr, paid: formatPrice(shown.amount, shown.currency) });
        setStep("done");
      } else if (outcome.kind === "declined") {
        // A definite "no": the next try is a new attempt, so it gets a new key.
        idempotencyKey.current = crypto.randomUUID();
        setNotice(`${outcome.detail}. You have not been charged. Choose another payment method and try again.`);
      } else if (outcome.kind === "price-changed") {
        idempotencyKey.current = crypto.randomUUID();
        setNotice(`${outcome.detail} Check the new total and confirm again.`);
        fetchQuote();
      } else if (outcome.kind === "payment-unavailable") {
        // Safe to retry with the same key: the payment provider never charges one key twice.
        setNotice("We couldn't reach the payment provider. You have not been charged. Please try again in a moment.");
      } else if (outcome.kind === "seat-taken") {
        setHold(null);
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
    return <Confirmation pnr={booked.pnr} paid={booked.paid} />;
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
              disabled={!allSeated || pending}
              onClick={continueToPassengers}
              className="w-full rounded-md bg-copper px-4 py-3 font-semibold text-white hover:bg-copper-strong disabled:cursor-not-allowed disabled:opacity-50"
            >
              {pending ? "Holding your seats…" : "Continue to passengers"}
            </button>
            {!allSeated && <p className="text-xs text-muted">Choose a seat for every passenger on every flight.</p>}
          </aside>
        </section>
      )}

      {step === "passengers" && (
        <form onSubmit={goToReview} className="max-w-xl space-y-6">
          {hold && <HoldCountdown expiresAt={hold.expiresAt} onExpire={holdEnded} />}
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
            <button type="button" onClick={backToSeats} className="rounded-md border border-sand px-5 py-3">
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
          {hold && <HoldCountdown expiresAt={hold.expiresAt} onExpire={holdEnded} />}
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
          <PaymentSummary quote={quote} method={paymentToken} onMethodChange={setPaymentToken} disabled={pending} />
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
              disabled={pending || quote?.kind !== "quoted"}
              onClick={confirm}
              className="rounded-md bg-copper px-6 py-3 font-semibold text-white hover:bg-copper-strong disabled:opacity-60"
            >
              {pending ? "Processing…" : quote?.kind === "quoted" ? `Pay ${formatPrice(quote.amount, quote.currency)} and book` : "Pay and book"}
            </button>
          </div>
        </section>
      )}
    </div>
  );
}

function Confirmation({ pnr, paid }: { pnr: Pnr; paid: string }) {
  return (
    <section aria-labelledby="done-heading" className="max-w-xl rounded-lg border border-sand bg-white p-8">
      <p className="text-sm font-semibold text-copper">Booking confirmed</p>
      <h2 id="done-heading" className="mt-1 font-display text-3xl">
        Your booking reference
      </h2>
      <p className="mt-4 font-mono text-4xl tracking-[0.3em]" aria-label={`Booking reference ${pnr.locator.split("").join(" ")}`}>
        {pnr.locator}
      </p>
      <p className="mt-4 text-sm">
        Paid <span className="font-mono font-semibold">{paid}</span>
      </p>
      <p className="mt-2 text-muted">
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
