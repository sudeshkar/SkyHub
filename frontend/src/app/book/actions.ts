"use server";

import {
  createHold,
  createPnr,
  getQuote,
  releaseHold,
  type BookingOutcome,
  type HoldOutcome,
  type QuoteOutcome,
  type Segment,
} from "@/lib/api/booking";
import { POLICIES, rateLimit } from "@/lib/rate-limit";

export type CreateBookingResult = BookingOutcome | { kind: "rate-limited"; retryAfterSeconds: number };

export type HoldSeatsResult = HoldOutcome | { kind: "rate-limited"; retryAfterSeconds: number };

export type QuoteResult = QuoteOutcome | { kind: "rejected"; detail: string };

export type BookingInput = {
  /** Issued by the payment provider; card details never reach SkyHub. */
  paymentToken: string;
  /** The price shown to the traveller, so a changed price is caught before anything is charged. */
  expectedAmount?: number;
  /** Hold made when the seats were chosen; the booking turns it into the real thing. */
  holdId?: string;
  contactEmail: string;
  segments: Segment[];
  passengers: { firstName: string; lastName: string; seats: string[] }[];
};

const NAME = /^[\p{L}][\p{L}' .-]{0,49}$/u;
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const SEAT = /^[0-9]{1,2}[A-K]$/;
const IDEMPOTENCY_KEY = /^[0-9a-f-]{36}$/;
const PAYMENT_TOKEN = /^tok_[a-z0-9_]{1,40}$/;
const HOLD_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const FLIGHT = /^[A-Z0-9]{2,3}[0-9]{1,4}$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** The price of the trip, worked out by the booking service. Checked again here: Server Actions are public endpoints. */
export async function quoteTrip(segments: Segment[], passengerCount: number): Promise<QuoteResult> {
  const valid =
    Number.isInteger(passengerCount) &&
    passengerCount >= 1 &&
    passengerCount <= 9 &&
    segments.length >= 1 &&
    segments.length <= 4 &&
    segments.every((s) => FLIGHT.test(s.flightNumber) && DATE.test(s.departureDate));
  if (!valid) return { kind: "rejected", detail: "We couldn't price this trip." };
  return getQuote(segments, passengerCount);
}

/**
 * Holds the chosen seats while the traveller fills in details. seats[passenger][segment], like the wizard keeps
 * them. Checked again here: Server Actions are public endpoints.
 */
export async function holdSeats(segments: Segment[], seats: string[][]): Promise<HoldSeatsResult> {
  const decision = await rateLimit(POLICIES.hold);
  if (!decision.allowed) return { kind: "rate-limited", retryAfterSeconds: decision.retryAfterSeconds };

  const valid =
    segments.length >= 1 &&
    segments.length <= 4 &&
    segments.every((s) => FLIGHT.test(s.flightNumber) && DATE.test(s.departureDate)) &&
    seats.length >= 1 &&
    seats.length <= 9 &&
    seats.every((row) => row.length === segments.length && row.every((seat) => SEAT.test(seat)));
  if (!valid) return { kind: "rejected", detail: "Please choose a valid seat for every passenger." };

  return createHold(
    segments.flatMap((segment, i) =>
      seats.map((row) => ({
        flightNumber: segment.flightNumber,
        departureDate: segment.departureDate,
        seatNumber: row[i],
      })),
    ),
  );
}

/** Lets go of a hold early (the traveller went back). A malformed id is ignored. */
export async function releaseSeats(holdId: string): Promise<void> {
  if (HOLD_ID.test(holdId)) await releaseHold(holdId);
}

/** Server Actions are public HTTP endpoints, so the input is checked again here, never trusted. */
export async function createBooking(input: BookingInput, idempotencyKey: string): Promise<CreateBookingResult> {
  const decision = await rateLimit(POLICIES.booking);
  if (!decision.allowed) return { kind: "rate-limited", retryAfterSeconds: decision.retryAfterSeconds };

  const contactEmail = input.contactEmail.trim();
  const valid =
    IDEMPOTENCY_KEY.test(idempotencyKey) &&
    (input.holdId === undefined || HOLD_ID.test(input.holdId)) &&
    PAYMENT_TOKEN.test(input.paymentToken) &&
    (input.expectedAmount === undefined || (Number.isFinite(input.expectedAmount) && input.expectedAmount > 0)) &&
    EMAIL.test(contactEmail) &&
    contactEmail.length <= 254 &&
    input.segments.length >= 1 &&
    input.passengers.length >= 1 &&
    input.passengers.length <= 9 &&
    input.passengers.every(
      (p) =>
        NAME.test(p.firstName.trim()) &&
        NAME.test(p.lastName.trim()) &&
        p.seats.length === input.segments.length &&
        p.seats.every((seat) => SEAT.test(seat)),
    );
  if (!valid) {
    return { kind: "rejected", detail: "Please check the passenger names, email and seats." };
  }

  return createPnr(
    {
      ...(input.holdId ? { holdId: input.holdId } : {}),
      paymentToken: input.paymentToken,
      ...(input.expectedAmount !== undefined ? { expectedAmount: input.expectedAmount } : {}),
      contactEmail,
      segments: input.segments,
      passengers: input.passengers.map((p) => ({
        firstName: p.firstName.trim(),
        lastName: p.lastName.trim(),
        seats: p.seats,
      })),
    },
    idempotencyKey,
  );
}
