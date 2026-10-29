"use server";

import { createPnr, type BookingOutcome, type Segment } from "@/lib/api/booking";
import { POLICIES, rateLimit } from "@/lib/rate-limit";

export type CreateBookingResult = BookingOutcome | { kind: "rate-limited"; retryAfterSeconds: number };

export type BookingInput = {
  contactEmail: string;
  segments: Segment[];
  passengers: { firstName: string; lastName: string; seats: string[] }[];
};

const NAME = /^[\p{L}][\p{L}' .-]{0,49}$/u;
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const SEAT = /^[0-9]{1,2}[A-K]$/;
const IDEMPOTENCY_KEY = /^[0-9a-f-]{36}$/;

/** Server Actions are public HTTP endpoints, so the input is checked again here, never trusted. */
export async function createBooking(input: BookingInput, idempotencyKey: string): Promise<CreateBookingResult> {
  const decision = await rateLimit(POLICIES.booking);
  if (!decision.allowed) return { kind: "rate-limited", retryAfterSeconds: decision.retryAfterSeconds };

  const contactEmail = input.contactEmail.trim();
  const valid =
    IDEMPOTENCY_KEY.test(idempotencyKey) &&
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
