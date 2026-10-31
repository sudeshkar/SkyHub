import { expect, test, type APIRequestContext, type Page } from "@playwright/test";

/**
 * Check-in, check-out, close session and the undo-email page, in a real browser (ADR-0058, ADR-0057, ADR-0054). Needs the whole stack and the
 * website: start the services with `python tools/live-check/stack.py up` (it opens the check-in window wide enough for these flights, ADR-0059;
 * a real window is 24 hours), start Redis (`docker compose up -d redis`, the website's rate limit), run the website, then `npm run e2e`.
 * Set SKYHUB_MAILPIT_URL (for example http://localhost:8025) to also run the test that needs the notification service's emails.
 */
const SEARCH_API = process.env.SKYHUB_SEARCH_API_URL ?? "http://localhost:8081";
const BOOKING_API = process.env.SKYHUB_BOOKING_API_URL ?? "http://localhost:8082";
const MAILPIT = process.env.SKYHUB_MAILPIT_URL;

type Seat = { seatNumber: string; status: string; cabin: string };

/** A direct DXB to LHR flight a few days out and three free economy seats on it: two for the booking, one to move to. */
async function aFlightWithSeats(request: APIRequestContext): Promise<{ flight: string; date: string; seats: string[] }> {
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
async function bookTwo(request: APIRequestContext): Promise<{ locator: string; flight: string; spare: string }> {
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

async function findBooking(page: Page, locator: string) {
  await page.goto("/manage");
  await page.getByLabel("Booking reference").fill(locator);
  await page.getByLabel("Last name").fill("Perera");
  await page.getByRole("button", { name: "Find booking" }).click();
  await expect(page.getByText("Confirmed")).toBeVisible();
}

test("check in one passenger at a time, change the other's seat, check out, and check in again", async ({ page, request }) => {
  const { locator, flight } = await bookTwo(request);
  await findBooking(page, locator);

  // Ravi checks in alone: only his pass shows, only his seat is frozen, and the flight says 1 of 2.
  await page.getByRole("button", { name: `Check in Ravi for ${flight}` }).click();
  await expect(page.getByText("PERERA/RAVI", { exact: true })).toBeVisible();
  await expect(page.getByText("PERERA/MIRA", { exact: true })).toHaveCount(0);
  await expect(page.getByText("1 of 2 checked in")).toBeVisible();
  await expect(page.getByRole("button", { name: `Change seat for Ravi Perera on ${flight}` })).toBeDisabled();
  await expect(page.getByRole("button", { name: `Change seat for Mira Perera on ${flight}` })).toBeEnabled();

  // Mira, who is not checked in, can still move.
  await page.getByRole("button", { name: `Change seat for Mira Perera on ${flight}` }).click();
  await page.getByRole("button", { name: /^Seat .*, available$/ }).first().click();
  await page.getByRole("button", { name: /^Move to seat / }).click();
  await expect(page.getByRole("status")).toContainText("Seat changed");

  // The flight's own button checks in everyone who is left, and Ravi keeps his pass.
  await page.getByRole("button", { name: `Check in for ${flight}` }).click();
  await expect(page.getByText("PERERA/MIRA", { exact: true })).toBeVisible();
  await expect(page.getByText("PERERA/RAVI", { exact: true })).toBeVisible();
  await expect(page.getByText("Checked in", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: `Check in for ${flight}` })).toHaveCount(0);

  // Mira checks out: her pass goes, Ravi's stays, and her seat is free to change again.
  await page.getByRole("button", { name: `Check out Mira of ${flight}` }).click();
  await expect(page.getByRole("status")).toContainText("Checked out");
  await expect(page.getByText("PERERA/MIRA", { exact: true })).toHaveCount(0);
  await expect(page.getByText("PERERA/RAVI", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: `Change seat for Mira Perera on ${flight}` })).toBeEnabled();
  await expect(page.getByRole("button", { name: `Change seat for Ravi Perera on ${flight}` })).toBeDisabled();

  // Everyone checks out, then in again.
  await page.getByRole("button", { name: `Check out of ${flight}` }).click();
  await expect(page.getByText("PERERA/RAVI", { exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: `Check in for ${flight}` }).click();
  await expect(page.getByText("PERERA/MIRA", { exact: true })).toBeVisible();
  await expect(page.getByText("PERERA/RAVI", { exact: true })).toBeVisible();
});

test("closing the booking ends the session for a copy of its token too", async ({ page, context, request }) => {
  const { locator } = await bookTwo(request);
  await findBooking(page, locator);

  const cookie = (await context.cookies()).find((c) => c.name === "skyhub_manage");
  expect(cookie).toBeDefined();
  const token = (JSON.parse(decodeURIComponent(cookie!.value)) as { token: string }).token; // what somebody who saw the cookie would hold
  const ask = () => request.get(`${BOOKING_API}/api/v1/pnrs/${locator}`, { headers: { Authorization: `Bearer ${token}` } });
  expect((await ask()).status()).toBe(200);

  await page.getByRole("button", { name: "Close this booking" }).click();
  await expect(page.getByRole("button", { name: "Find booking" })).toBeVisible();

  expect((await ask()).status()).toBe(401); // the copy is dead, not only the cookie
});

/** The newest message to this address whose subject has this text, read from Mailpit once it has arrived. */
async function mailTo(request: APIRequestContext, address: string, subjectPart: string): Promise<string> {
  let text = "";
  await expect
    .poll(
      async () => {
        const list = await request.get(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:${address}`)}`);
        const found = ((await list.json()) as { messages: { ID: string; Subject: string }[] }).messages.find((m) => m.Subject.includes(subjectPart));
        if (!found) return false;
        text = ((await (await request.get(`${MAILPIT}/api/v1/message/${found.ID}`)).json()) as { Text: string }).Text;
        return true;
      },
      { timeout: 45_000 },
    )
    .toBe(true);
  return text;
}

function tokenIn(mail: string, page: string): string {
  const found = new RegExp(`${page}\\?token=([A-Za-z0-9_%.\\-]+)`).exec(mail);
  expect(found, `a link to ${page} in the email`).not.toBeNull();
  return decodeURIComponent(found![1]);
}

test("the owner of the old address takes an account back with the link in the notice", async ({ page, request }) => {
  test.skip(!MAILPIT, "set SKYHUB_MAILPIT_URL (and run the notification service) for the emailed links");
  const run = crypto.randomUUID().slice(0, 8);
  const owner = `e2e.owner.${run}@example.com`;
  const intruder = `e2e.intruder.${run}@example.com`;
  const password = "a long enough passphrase";
  const api = (path: string, data: unknown, token?: string) =>
    request.post(`${BOOKING_API}${path}`, { data, headers: token ? { Authorization: `Bearer ${token}` } : {} });

  // The owner signs up; somebody who knows the password moves the account to an address of theirs.
  expect((await api("/api/v1/accounts/password-links", { email: owner })).status()).toBe(202);
  const signUp = await mailTo(request, owner, "Finish creating");
  expect((await api("/api/v1/accounts/passwords", { token: tokenIn(signUp, "set-password"), password })).status()).toBe(204);
  const session = (await (await api("/api/v1/accounts/sessions", { email: owner, password })).json()) as { accessToken: string };
  expect((await api("/api/v1/accounts/me/email-change", { newEmail: intruder, password }, session.accessToken)).status()).toBe(202);
  const confirm = await mailTo(request, intruder, "Confirm your new");
  expect((await api("/api/v1/accounts/me/email-change/confirmation", { token: tokenIn(confirm, "confirm-email") }, session.accessToken)).status()).toBe(204);

  // The owner reads the notice at the old address and follows its link. Opening it changes nothing by itself.
  const notice = await mailTo(request, owner, "was changed");
  const link = tokenIn(notice, "undo-email-change");
  await page.goto(`/account/undo-email-change?token=${encodeURIComponent(link)}`);
  await expect(page.getByRole("heading", { name: "Take your account back" })).toBeVisible();
  expect((await api("/api/v1/accounts/sessions", { email: intruder, password })).status()).toBe(201); // still moved: a page view undid nothing

  await page.getByRole("button", { name: "Take my account back" }).click();
  await expect(page.getByRole("status")).toContainText("Your account is yours again");

  // The person who moved it is shut out, the password is gone, and a link to choose a new one reaches the owner.
  expect((await api("/api/v1/accounts/sessions", { email: intruder, password })).status()).toBe(401);
  expect((await api("/api/v1/accounts/sessions", { email: owner, password })).status()).toBe(401);
  await mailTo(request, owner, "Reset your SkyHub password");

  // The same link does not work twice.
  await page.goto(`/account/undo-email-change?token=${encodeURIComponent(link)}`);
  await page.getByRole("button", { name: "Take my account back" }).click();
  await expect(page.getByText(/This link no longer works/)).toBeVisible();
});
