import { expect, test, type APIRequestContext } from "@playwright/test";

/**
 * The whole approval flow in a real browser (ADR-0024): travellers book, staff record a cancellation, ask the agent for
 * proposals, approve them, and the bookings really move. Needs the disruption agent running and the website started with
 * SKYHUB_AGENT_API_URL and SKYHUB_AGENT_API_TOKEN, plus a staff account (see ops-disruption.spec.ts). Skipped otherwise.
 */
const USER = process.env.SKYHUB_E2E_STAFF_USER;
const PASSWORD = process.env.SKYHUB_E2E_STAFF_PASSWORD;
const AGENT = process.env.SKYHUB_E2E_AGENT; // set to 1 when the agent service is running
const BOOKING_API = process.env.SKYHUB_BOOKING_API_URL ?? "http://localhost:8082";
const SEARCH_API = process.env.SKYHUB_SEARCH_API_URL ?? "http://localhost:8081";

/** A far-off date with a direct DXB to LHR flight today and tomorrow, so there is somewhere to move to. */
async function dateWithTwoDays(request: APIRequestContext): Promise<string> {
  const start = 150 + Math.floor(Math.random() * 200);
  for (let offset = 0; offset < 20; offset++) {
    const day = (n: number) => new Date(Date.now() + (start + offset + n) * 86_400_000).toISOString().slice(0, 10);
    const direct = async (d: string) => {
      const r = await request.get(`${SEARCH_API}/api/v1/itineraries?origin=DXB&destination=LHR&date=${d}&passengers=1`);
      return r.ok() && ((await r.json()) as { stops: number }[]).some((i) => i.stops === 0);
    };
    if ((await direct(day(0))) && (await direct(day(1)))) return day(0);
  }
  throw new Error("No two consecutive days with a direct DXB to LHR flight");
}

async function book(request: APIRequestContext, day: string, lastName: string, seats: string[]): Promise<string> {
  const response = await request.post(`${BOOKING_API}/api/v1/pnrs`, {
    headers: { "Idempotency-Key": crypto.randomUUID() },
    data: {
      paymentToken: "tok_visa", contactEmail: `${lastName.toLowerCase()}@example.com`,
      segments: [{ flightNumber: "SK001", departureDate: day }],
      passengers: seats.map((seat, i) => ({ firstName: `P${i}`, lastName, seats: [seat] })),
    },
  });
  expect(response.status()).toBe(201);
  return ((await response.json()) as { locator: string }).locator;
}

async function freeSeats(request: APIRequestContext, day: string): Promise<string[]> {
  const map = (await (await request.get(`${BOOKING_API}/api/v1/flights/SK001/departures/${day}/seats`)).json()) as {
    seats: { seatNumber: string; status: string }[];
  };
  return map.seats.filter((s) => s.status === "AVAILABLE").map((s) => s.seatNumber);
}

async function flightOf(request: APIRequestContext, locator: string, lastName: string): Promise<string> {
  const session = (await (await request.post(`${BOOKING_API}/api/v1/pnrs/${locator}/sessions`, { data: { lastName } })).json()) as {
    pnr: { segments: { flightNumber: string; departureDate: string }[] };
  };
  return `${session.pnr.segments[0].flightNumber} ${session.pnr.segments[0].departureDate}`;
}

test("staff approve the agent's proposals and the bookings really move", async ({ page, request }) => {
  test.skip(!USER || !PASSWORD || !AGENT, "needs a staff account and the agent service (SKYHUB_E2E_AGENT=1)");
  const day = await dateWithTwoDays(request);
  const next = new Date(Date.parse(day) + 86_400_000).toISOString().slice(0, 10);
  const seats = await freeSeats(request, day);
  const first = await book(request, day, "Ana", seats.slice(0, 2));
  const second = await book(request, day, "Ben", seats.slice(2, 5));

  await page.goto("/ops");
  await page.getByLabel("Username").fill(USER!);
  await page.getByLabel("Password").fill(PASSWORD!);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByText("Signed in as")).toContainText(USER!);

  // Record the cancellation in the UI.
  await page.getByLabel("Flight number").fill("SK001");
  await page.getByLabel(/Scheduled departure date/).first().fill(day);
  await page.getByLabel("Cancelled").check();
  await page.getByRole("button", { name: "Record", exact: true }).click();
  await expect(page.getByRole("status").first()).toContainText("is recorded as cancelled");

  // Ask for proposals for the same flight.
  const panel = page.getByRole("region", { name: "Proposals" });
  await panel.getByLabel("Disrupted flight").fill("SK001");
  await panel.getByLabel(/Scheduled departure date/).fill(day);
  await panel.getByRole("button", { name: "Get proposals" }).click();
  await expect(panel.getByText(first)).toBeVisible();
  await expect(panel.getByText(second)).toBeVisible();
  await expect(panel.getByText(/Planned by the rule-based first-fit planner/)).toBeVisible();
  await expect(panel.getByText(`SK001 on ${next}`).first()).toBeVisible();

  // Nothing has moved yet: it needs an approval.
  expect(await flightOf(request, first, "Ana")).toBe(`SK001 ${day}`);

  // Approve one booking only: untick the second.
  await panel.getByRole("checkbox", { name: `Move ${second}` }).uncheck();
  await panel.getByRole("button", { name: "Move 1 selected" }).click();
  await expect(panel.getByText(/Moved · seats/)).toBeVisible();

  expect(await flightOf(request, first, "Ana")).toBe(`SK001 ${next}`);
  expect(await flightOf(request, second, "Ben")).toBe(`SK001 ${day}`); // not approved, so untouched

  // It stayed unticked, so nothing more can be moved until the person ticks it; then approve it too.
  await expect(panel.getByRole("button", { name: "Move 0 selected" })).toBeDisabled();
  await panel.getByRole("checkbox", { name: `Move ${second}` }).check();
  await panel.getByRole("button", { name: "Move 1 selected" }).click();
  await expect(panel.getByRole("status")).toContainText("2 moved");
  expect(await flightOf(request, second, "Ben")).toBe(`SK001 ${next}`);
});
