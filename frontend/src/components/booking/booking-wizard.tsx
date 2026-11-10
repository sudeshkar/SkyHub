"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useRef, useState, useTransition } from "react";
import { loadSavedPassengers, type SavedName } from "@/lib/saved-passengers-client";
import { createBooking, holdSeats, quoteTrip, releaseSeats } from "@/app/book/actions";
import type { QuoteOutcome, SeatMap as SeatMapData, Segment } from "@/lib/api/booking";
import { rememberBooking, tripKey } from "@/lib/recent-booking";
import { formatPrice } from "@/lib/format";
import { Modal } from "@/components/modal";
import { useRecentBooking } from "./booking-confirmation";
import { HoldCountdown } from "./hold-countdown";
import { DEFAULT_PAYMENT_TOKEN, PaymentSummary } from "./payment-summary";
import { SeatMap } from "./seat-map";

type Step = "seats" | "passengers" | "review";
type Passenger = { firstName: string; lastName: string };

const STEPS: { id: Step; label: string }[] = [
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
  /** Where the traveller's saved names come from (ADR-0078); replaced in tests. */
  loadSaved?: () => Promise<SavedName[]>;
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

function Wizard({ segments, seatMaps, passengerCount, loadSaved = loadSavedPassengers }: WizardProps) {
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
  // The names this traveller has saved to their account, asked for only when they reach the passengers step (ADR-0078).
  const [saved, setSaved] = useState<SavedName[]>([]);
  const [notice, setNotice] = useState<string | null>(null);
  // What to do next while choosing seats, such as moving on to the connecting flight.
  const [guide, setGuide] = useState<string | null>(null);
  // These flights were booked in this tab a moment ago: warn before they are booked again by mistake (not a block, as
  // booking the same flights twice can be meant, such as for someone else). Hidden once the traveller chooses to go on.
  const recent = useRecentBooking();
  const [bookingAgain, setBookingAgain] = useState(false);
  const alreadyBooked = recent && recent.trip === tripKey(segments) && !bookingAgain ? recent : null;
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

  const flightDone = (s: number, rows: string[][] = seats) => rows.every((row) => Boolean(row[s]));
  const firstUnseated = (s: number, rows: string[][] = seats) => Math.max(0, rows.findIndex((row) => !row[s]));
  /** The next flight that still needs seats, looking forward from `from` first, then back; -1 when every flight is done. */
  const nextOpenFlight = (from: number, rows: string[][] = seats) => {
    const order = [...segments.keys()].filter((s) => s !== from);
    const ahead = order.filter((s) => s > from);
    return [...ahead, ...order.filter((s) => s < from)].find((s) => !flightDone(s, rows)) ?? -1;
  };

  function goToFlight(s: number, rows: string[][] = seats) {
    setSegmentIndex(s);
    setPassengerIndex(firstUnseated(s, rows));
  }

  /**
   * Choosing a seat leads the traveller on: to the next passenger still without a seat on this flight, then to the next
   * flight that needs seats, saying so, so nobody is left wondering why they cannot continue (a booking with a connection
   * has a seat map per flight). Choosing the same seat again clears it, and leads nowhere.
   */
  function chooseSeat(seatNumber: string) {
    const next = seats.map((row) => [...row]);
    const clearing = next[passengerIndex][segmentIndex] === seatNumber;
    next[passengerIndex][segmentIndex] = clearing ? "" : seatNumber;
    setSeats(next);
    if (clearing) {
      setGuide(null);
      return;
    }
    const waiting = next.findIndex((row) => !row[segmentIndex]);
    if (waiting !== -1) {
      setPassengerIndex(waiting);
      return;
    }
    const flight = nextOpenFlight(segmentIndex, next);
    if (flight !== -1) {
      goToFlight(flight, next);
      setGuide(
        `Seat ${seatNumber} chosen on ${segments[segmentIndex].flightNumber}. Now choose a seat on ${segments[flight].flightNumber}.`,
      );
    } else {
      setGuide("Every seat is chosen. Continue to passengers when you are ready.");
    }
  }

  function continueToPassengers() {
    setNotice(null);
    startTransition(async () => {
      const outcome = await holdSeats(segments, seats);
      if (outcome.kind === "held") {
        setHold({ id: outcome.holdId, expiresAt: outcome.expiresAt });
        setStep("passengers");
        // Not waited for, and never able to fail the booking: if it does not answer there is simply nothing to pick from.
        loadSaved().then(setSaved, () => setSaved([]));
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
        rememberBooking({
          locator: outcome.pnr.locator,
          contactEmail: outcome.pnr.contactEmail,
          paid: formatPrice(shown.amount, shown.currency),
          trip: tripKey(segments),
          passengers: outcome.pnr.passengers.map((p) => ({ firstName: p.firstName, lastName: p.lastName, seats: p.seats })),
        });
        // Replaces this page in the history: Back then goes to the flight results, and a refresh or Forward shows the
        // confirmation again instead of an empty seat map for flights already paid for.
        router.replace("/book/confirmed");
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

  const seatMap = seatMaps[segmentIndex];

  // The step's Back and Next buttons sit beside the heading, which stays at the top of the screen while the traveller
  // scrolls, so moving on never means hunting for a button at the bottom of a long form.
  const actions =
    step === "seats" ? (
      // Back to the flight results the traveller came from; a booking link opened on its own has none, so it starts again.
      <button
        type="button"
        onClick={() => (window.history.length > 1 ? router.back() : router.push("/"))}
        aria-label="Back to flight results"
        className="flex items-center gap-2 rounded-full border border-line bg-white px-5 py-2.5 hover:border-accent hover:text-accent"
      >
        <svg viewBox="0 0 20 20" className="size-4" aria-hidden="true">
          <path d="M16 10H4m5-5-5 5 5 5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
        Back
      </button>
    ) : step === "passengers" ? (
      <>
        <button type="button" onClick={backToSeats} className="rounded-full border border-line bg-white px-5 py-2.5">
          Back
        </button>
        <button
          type="submit"
          form="passenger-details"
          className="btn-primary px-6 py-2.5 font-semibold text-white"
        >
          Review booking
        </button>
      </>
    ) : step === "review" ? (
      <>
        <button
          type="button"
          disabled={pending}
          onClick={() => setStep("passengers")}
          className="rounded-full border border-line bg-white px-5 py-2.5"
        >
          Back
        </button>
        <button
          type="button"
          disabled={pending || quote?.kind !== "quoted"}
          onClick={confirm}
          className="btn-primary px-6 py-2.5 font-semibold text-white disabled:opacity-60"
        >
          {pending ? "Processing…" : quote?.kind === "quoted" ? `Pay ${formatPrice(quote.amount, quote.currency)} and book` : "Pay and book"}
        </button>
      </>
    ) : null;

  return (
    <div>
      <div className="sticky top-20 z-30 -mx-4 mb-4 flex flex-wrap items-center justify-between gap-x-6 gap-y-3 rounded-b-xl glass px-4 py-3">
        <h1 className="font-display text-3xl">Complete your booking</h1>
        {actions && <div className="flex gap-3">{actions}</div>}
      </div>
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

      {/* A pop-up rather than a banner, so it takes no room from the seat map; closing it in any way means booking again. */}
      {alreadyBooked && step === "seats" && (
        <Modal role="alertdialog" title="You have just booked these flights" onClose={() => setBookingAgain(true)}>
          <p className="text-sm text-muted">
            You booked these flights a moment ago ({alreadyBooked.locator}). Book them again only if you mean to, such as for
            someone else.
          </p>
          <div className="mt-5 flex flex-wrap gap-3">
            <Link href="/book/confirmed" className="btn-primary px-5 py-2.5 font-semibold">
              See that booking
            </Link>
            <button
              type="button"
              onClick={() => setBookingAgain(true)}
              className="rounded-full border border-line bg-white px-5 py-2.5 hover:border-accent hover:text-accent"
            >
              Book again
            </button>
          </div>
        </Modal>
      )}

      {notice && (
        <p role="alert" className="mb-6 border-l-4 border-danger bg-white px-4 py-3 text-sm text-danger">
          {notice}
        </p>
      )}

      {step === "seats" && (
        <section aria-labelledby="seats-heading" className="grid gap-8 lg:grid-cols-[1fr_18rem]">
          {/* min-w-0: lets the sideways seat map scroll inside its column instead of widening the page.
              Pulled up on wider screens: the space above the plane is kept for its wing, and the tabs and key sit in it, so
              this closes the empty strip between the steps and the tabs. Phones keep it, as the rows there can wrap. */}
          <div className="min-w-0 sm:-mt-12">
            <h2 id="seats-heading" className="sr-only">
              Choose seats
            </h2>
            <SeatMap
              seats={seatMap.seats}
              selected={seats[passengerIndex][segmentIndex] || undefined}
              heldByParty={heldByParty}
              onSelect={chooseSeat}
              header={
                segments.length > 1 && (
                  <div role="tablist" aria-label="Flights" className="flex gap-2">
                    {segments.map((segment, i) => (
                      <button
                        key={segment.flightNumber}
                        role="tab"
                        aria-selected={i === segmentIndex}
                        data-complete={flightDone(i)}
                        title={flightDone(i) ? "Seats chosen" : "Seats still to choose"}
                        onClick={() => {
                          goToFlight(i);
                          setGuide(null);
                        }}
                        className={`flex items-center gap-1.5 rounded-md border px-4 py-2 font-mono text-sm ${
                          i === segmentIndex ? "border-ink bg-ink text-canvas" : "border-line bg-white"
                        }`}
                      >
                        {segment.flightNumber}
                        {flightDone(i) && (
                          <svg viewBox="0 0 20 20" className={`size-4 ${i === segmentIndex ? "text-sky" : "text-accent"}`} aria-hidden="true">
                            <path d="M4.5 10.5l3.5 3.5 7.5-8" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" />
                          </svg>
                        )}
                      </button>
                    ))}
                  </div>
                )
              }
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
                      p === passengerIndex ? "border-accent" : "border-line"
                    }`}
                  >
                    <span>Passenger {p + 1}</span>
                    <span className="font-mono">{row[segmentIndex] || "—"}</span>
                  </button>
                </li>
              ))}
            </ul>
            {guide && (
              <p role="status" className="rounded-lg border border-accent/30 bg-wash px-3 py-2.5 text-sm text-ink">
                {guide}
              </p>
            )}
            {/* The main button always does the next useful thing: on to a flight that still needs seats, or on to the
                passengers once every flight has them. */}
            {!allSeated && flightDone(segmentIndex) && nextOpenFlight(segmentIndex) !== -1 ? (
              <button
                type="button"
                onClick={() => {
                  goToFlight(nextOpenFlight(segmentIndex));
                  setGuide(null);
                }}
                className="flex w-full items-center justify-center gap-2 btn-primary px-4 py-3 font-semibold text-white"
              >
                Next flight: {segments[nextOpenFlight(segmentIndex)].flightNumber}
                <svg viewBox="0 0 20 20" className="size-4" aria-hidden="true">
                  <path d="M4 10h12m-5-5 5 5-5 5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
              </button>
            ) : (
              <button
                type="button"
                disabled={!allSeated || pending}
                onClick={continueToPassengers}
                className="w-full btn-primary px-4 py-3 font-semibold text-white disabled:cursor-not-allowed disabled:opacity-50"
              >
                {pending ? "Holding your seats…" : "Continue to passengers"}
              </button>
            )}
            {!allSeated && !flightDone(segmentIndex) && (
              <p className="text-xs text-muted">
                Choose a seat for Passenger {firstUnseated(segmentIndex) + 1} on {segments[segmentIndex].flightNumber}
                {nextOpenFlight(segmentIndex) !== -1 ? `, then on ${segments[nextOpenFlight(segmentIndex)].flightNumber}` : ""}.
              </p>
            )}
          </aside>
        </section>
      )}

      {step === "passengers" && (
        <form id="passenger-details" onSubmit={goToReview} className="max-w-xl space-y-6">
          {hold && <HoldCountdown expiresAt={hold.expiresAt} onExpire={holdEnded} />}
          {passengers.map((passenger, p) => (
            <fieldset key={p} className="rounded-lg border border-line bg-white p-5">
              <legend className="px-1 font-semibold">Passenger {p + 1}</legend>
              <p className="mb-3 text-sm text-muted">Enter names exactly as they appear on the passport.</p>
              {saved.length > 0 && (
                <label className="mb-3 block text-sm font-medium">
                  Use a saved passenger
                  <select
                    aria-label={`Use a saved passenger for passenger ${p + 1}`}
                    value=""
                    onChange={(e) => {
                      const chosen = saved[Number(e.target.value)];
                      if (chosen) setPassengers(passengers.map((x, i) => (i === p ? { firstName: chosen.firstName, lastName: chosen.lastName } : x)));
                    }}
                    className="mt-1 block w-full rounded-md border border-line px-3 py-2"
                  >
                    <option value="">Choose a saved passenger…</option>
                    {saved.map((name, i) => (
                      <option key={`${name.firstName}-${name.lastName}-${i}`} value={i}>{name.firstName} {name.lastName}</option>
                    ))}
                  </select>
                </label>
              )}
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
                      className="mt-1 block w-full rounded-md border border-line px-3 py-2"
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
              className="mt-1 block w-full rounded-md border border-line px-3 py-2"
            />
            <span className="mt-1 block text-xs text-muted">We send the booking confirmation here.</span>
          </label>
        </form>
      )}

      {step === "review" && (
        // Two columns from tablet width up (the trip on the left, payment on the right), so the whole review, and the Pay
        // button in the heading row, fit on one screen without scrolling.
        <section aria-labelledby="review-heading" className="space-y-4">
          <div className="flex flex-wrap items-baseline justify-between gap-x-6 gap-y-1">
            <h2 id="review-heading" className="text-xl font-semibold">
              Check your booking
            </h2>
            {hold && <HoldCountdown expiresAt={hold.expiresAt} onExpire={holdEnded} className="" />}
          </div>
          <div className="grid items-start gap-6 md:grid-cols-2">
            <dl className="divide-y divide-line rounded-lg border border-line bg-white">
              {passengers.map((p, i) => (
                <div key={i} className="flex justify-between gap-4 px-5 py-3">
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
          </div>
        </section>
      )}
    </div>
  );
}
