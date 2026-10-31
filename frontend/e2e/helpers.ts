import { expect, type APIRequestContext } from "@playwright/test";

/** Set-up shared by the specs that need a booking on the live stack (ADR-0061). The API makes the data; the browser does what a person does. */
export const SEARCH_API = process.env.SKYHUB_SEARCH_API_URL ?? "http://localhost:8081";
export const BOOKING_API = process.env.SKYHUB_BOOKING_API_URL ?? "http://localhost:8082";
export const MAILPIT = process.env.SKYHUB_MAILPIT_URL;

type Seat = { seatNumber: string; status: string; cabin: string };

/** A direct DXB to LHR flight a few days out and three free economy seats on it: two for the booking, one to move to. */
export async function aFlightWithSeats(request: APIRequestContext): Promise<{ flight: string; date: string; seats: string[] }> {
  const start = 3 + Math.floor(Math.random() * 40); // different runs, different dates, so seats are free
  for (let offset = 0; offset < 20; offset++) {
    const date = new Date(Date.now() + (start + offset) * 86_400_000).toISOString().slice(0, 10);
    const found = await request.get(`${SEARCH_API}/api/v1/itineraries?origin=DXB&destination=LHR&date=${date}&passengers=2`);
    if (!found.ok()) continue;
    const direct = ((await found.json()) as { stops: number; legs: { flightNumber: string; departureTime: string }[] }[]).find((i) => i.stops === 0);
    if (!direct) continue;
    const leg = direct.legs[0];
    const day = leg.departureTime.slice(0, 10);
    const map = await request.get(`${BOOKING_API}/api/v1/flights/${leg.flightNumber}/departures/${day}/seats`);
    if (!map.ok()) continue;
    const free = ((await map.json()) as { seats: Seat[] }).seats.filter((s) => s.status === "AVAILABLE" && s.cabin === "ECONOMY").map((s) => s.seatNumber);
    if (free.length >= 3) return { flight: leg.flightNumber, date: day, seats: free.slice(0, 3) };
  }
  throw new Error("No direct DXB to LHR flight with free seats in the next weeks");
}

/** A booking of two passengers (Mira and Ravi Perera), made through the API; the browser does the rest. */
export async function bookTwo(request: APIRequestContext): Promise<{ locator: string; flight: string; spare: string }> {
  const { flight, date, seats } = await aFlightWithSeats(request);
  const response = await request.post(`${BOOKING_API}/api/v1/pnrs`, {
    headers: { "Idempotency-Key": crypto.randomUUID() },
    data: {
      paymentToken: "tok_visa",
      contactEmail: `e2e.checkin.${crypto.randomUUID().slice(0, 8)}@example.com`,
      segments: [{ flightNumber: flight, departureDate: date }],
      passengers: [
        { firstName: "Mira", lastName: "Perera", seats: [seats[0]] },
        { firstName: "Ravi", lastName: "Perera", seats: [seats[1]] },
      ],
    },
  });
  expect(response.status()).toBe(201);
  return { locator: ((await response.json()) as { locator: string }).locator, flight, spare: seats[2] };
}

