import { expect, test, type APIRequestContext, type Page } from "@playwright/test";

const SEARCH_API = process.env.SKYHUB_SEARCH_API_URL ?? "http://localhost:8081";

/** The schedule is not daily, so ask the search service for a date that has a direct flight, a few weeks out. */
async function dateWithAFlight(request: APIRequestContext, origin: string, destination: string): Promise<string> {
  const start = 20 + Math.floor(Math.random() * 200); // different runs, different dates, so seats are free
  for (let offset = 0; offset < 14; offset++) {
    const date = new Date(Date.now() + (start + offset) * 86_400_000).toISOString().slice(0, 10);
    const response = await request.get(
      `${SEARCH_API}/api/v1/itineraries?origin=${origin}&destination=${destination}&date=${date}&passengers=1`,
    );
    if (!response.ok()) continue;
    const found = (await response.json()) as { stops: number }[];
    if (found.some((itinerary) => itinerary.stops === 0)) return date;
  }
  throw new Error(`No direct ${origin} to ${destination} flight found in the next weeks`);
}

/** Picks the first seat that is free, whatever cabin it is in. */
async function pickFirstFreeSeat(page: Page) {
  await page.getByRole("button", { name: /^Seat .*, available$/ }).first().click();
}

test("book a flight, pay, then cancel it and see the money come back", async ({ page, request }) => {
  const date = await dateWithAFlight(request, "DXB", "LHR");

  // Search and choose the direct flight.
  await page.goto(`/search?origin=DXB&destination=LHR&date=${date}`);
  await page.getByRole("link", { name: /^Select SK\d+$/ }).first().click();

  // Seats: one passenger, one flight. The booking holds the seat while the traveller fills in the form.
  await expect(page.getByRole("list", { name: "Booking steps" })).toBeVisible();
  await pickFirstFreeSeat(page);
  await page.getByRole("button", { name: "Continue to passengers" }).click();

  // Passengers. The hold countdown shows while the seat is held.
  await expect(page.getByText(/We are holding your seats for \d+:\d{2}/)).toBeVisible();
  await page.getByLabel("First name").fill("Priya");
  await page.getByLabel("Last name").fill("Perera");
  await page.getByLabel("Contact email").fill("priya@example.com");
  await page.getByRole("button", { name: "Review booking" }).click();

  // Review and pay with the approved test card.
  await expect(page.getByRole("heading", { name: "Check your booking" })).toBeVisible();
  await page.getByLabel(/Visa ending 4242/).check();
  await page.getByRole("button", { name: /^Pay .* and book$/ }).click();

  // Confirmation: the reference is shown once and is what the traveller keeps.
  await expect(page.getByRole("heading", { name: "Your booking reference" })).toBeVisible();
  const locator = (await page.getByLabel(/^Booking reference /).textContent())?.trim() ?? "";
  expect(locator).toMatch(/^[A-Z0-9]{6}$/);

  // Find the booking again with the reference and a passenger's last name.
  await page.goto("/manage");
  await page.getByLabel("Booking reference").fill(locator);
  await page.getByLabel("Last name").fill("Perera");
  await page.getByRole("button", { name: "Find booking" }).click();
  await expect(page.getByText("Confirmed")).toBeVisible();
  await expect(page.getByRole("region", { name: "Refund" })).toHaveCount(0);

  // Cancel: the dialog states the refund rule first.
  await page.getByRole("button", { name: "Cancel booking" }).click();
  await expect(page.getByRole("alertdialog")).toContainText("24 hours or more before the first flight");
  await page.getByRole("button", { name: "Yes, cancel booking" }).click();

  // The flight is weeks away, so the whole fare goes back.
  await expect(page.getByText("Cancelled", { exact: true })).toBeVisible();
  await expect(page.getByRole("region", { name: "Refund" })).toContainText("has been refunded to your original payment method");
});

test("a wrong last name does not reveal whether the booking exists", async ({ page }) => {
  await page.goto("/manage");
  await page.getByLabel("Booking reference").fill("ZZZZZZ");
  await page.getByLabel("Last name").fill("Nobody");
  await page.getByRole("button", { name: "Find booking" }).click();

  await expect(page.getByRole("alert")).toBeVisible();
});
