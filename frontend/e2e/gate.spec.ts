import { expect, test, type APIRequestContext } from "@playwright/test";
import { BOOKING_API, bookTwo } from "./helpers";

/**
 * The gate, in a real browser (ADR-0062). A gate agent signs in, sees only the gate, checks a pass, boards the passenger once, and is refused
 * the second time. Needs the stack from `python tools/live-check/stack.py up` (a wide check-in window, ADR-0059), Redis and the website, and
 * an administrator to create the gate agent: SKYHUB_E2E_STAFF_USER / SKYHUB_E2E_STAFF_PASSWORD, as for the ops spec. Skipped without them.
 */
const ADMIN = process.env.SKYHUB_E2E_STAFF_USER;
const ADMIN_PASSWORD = process.env.SKYHUB_E2E_STAFF_PASSWORD;

async function createGateAgent(request: APIRequestContext, username: string, password: string) {
  const admin = await request.post(`${BOOKING_API}/api/v1/ops/sessions`, { data: { username: ADMIN, password: ADMIN_PASSWORD } });
  expect(admin.status()).toBe(201);
  const token = ((await admin.json()) as { accessToken: string }).accessToken;
  const created = await request.post(`${BOOKING_API}/api/v1/ops/staff`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { username, password, role: "GATE" },
  });
  expect(created.status()).toBe(201);
}

/** Checks the whole booking in as its traveller would, and returns the barcodes of the two passes. */
async function checkedInPasses(request: APIRequestContext, locator: string): Promise<string[]> {
  const session = await request.post(`${BOOKING_API}/api/v1/pnrs/${locator}/sessions`, { data: { lastName: "Perera" } });
  const token = ((await session.json()) as { accessToken: string }).accessToken;
  const checkedIn = await request.post(`${BOOKING_API}/api/v1/pnrs/${locator}/check-ins`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { segmentIndex: 0 },
  });
  expect(checkedIn.status()).toBe(201);
  return ((await checkedIn.json()) as { passes: { barcode: string }[] }).passes.map((p) => p.barcode);
}

test("a gate agent sees only the gate, checks a pass, boards the passenger once and is refused the second time", async ({ page, request }) => {
  test.skip(!ADMIN || !ADMIN_PASSWORD, "set SKYHUB_E2E_STAFF_USER and SKYHUB_E2E_STAFF_PASSWORD");
  const agent = `e2e.gate.${crypto.randomUUID().slice(0, 8)}`;
  const password = "a long gate passphrase";
  await createGateAgent(request, agent, password);
  const { locator } = await bookTwo(request);
  const [mira, ravi] = await checkedInPasses(request, locator);

  // Signing in as a gate agent works (the website once treated this role as "not signed in") and lands on the gate, nothing else.
  await page.goto("/ops");
  await page.getByLabel("Username").fill(agent);
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByText("Signed in as")).toContainText("gate agent");
  await expect(page.getByRole("region", { name: "Gate" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Record" })).toHaveCount(0); // the operations forms are not offered
  await expect(page.getByRole("heading", { name: "Withdraw a disruption" })).toHaveCount(0);

  const pass = page.getByLabel("Scanned boarding pass");
  const result = page.getByRole("region", { name: "Gate" });
  /** One scan: type the pass, press the button, and wait for the field to clear (the pass is not kept) before the next scan. */
  async function scan(text: string, button: "Check pass" | "Board passenger") {
    await pass.fill(text);
    await page.getByRole("button", { name: button }).click();
    await expect(pass).toHaveValue("");
  }

  // Checking a pass shows who and where, and says it has not boarded them.
  await scan(mira, "Check pass");
  await expect(result.getByRole("status")).toContainText("Good pass for PERERA/MIRA");
  await expect(result.getByRole("status")).toContainText("Not boarded yet");

  // Boarding boards them.
  await scan(mira, "Board passenger");
  await expect(result.getByRole("status")).toContainText("PERERA/MIRA has boarded");

  // The same pass again is refused, whether it is boarded or only checked, and says nothing about the passenger.
  await scan(mira, "Board passenger");
  await expect(result.getByRole("alert")).toContainText("already boarded");
  await expect(result.getByRole("alert")).not.toContainText("PERERA");
  await scan(mira, "Check pass");
  await expect(result.getByRole("alert")).toContainText("already boarded");

  // The other passenger is not affected: checking never boarded anyone.
  await scan(ravi, "Check pass");
  await expect(result.getByRole("status")).toContainText("Good pass for PERERA/RAVI");

  // Something that is not a pass is refused in plain words.
  await scan(mira.replace(/ \d{4}\./, " 9999."), "Board passenger");
  await expect(result.getByRole("alert")).toContainText("not a SkyHub boarding pass");
});
