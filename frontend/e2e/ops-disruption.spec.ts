import { expect, test } from "@playwright/test";

/**
 * Needs a staff account on the running booking service. Start the service with
 * SKYHUB_BOOTSTRAP_ADMIN_USERNAME / SKYHUB_BOOTSTRAP_ADMIN_PASSWORD and pass the same pair here as
 * SKYHUB_E2E_STAFF_USER / SKYHUB_E2E_STAFF_PASSWORD. Without them the test is skipped, not failed.
 */
const USER = process.env.SKYHUB_E2E_STAFF_USER;
const PASSWORD = process.env.SKYHUB_E2E_STAFF_PASSWORD;

test("a wrong password gets one plain message and no session", async ({ page }) => {
  await page.goto("/ops");
  await page.getByLabel("Username").fill("nobody.here");
  await page.getByLabel("Password").fill("not-a-real-password");
  await page.getByRole("button", { name: "Sign in" }).click();

  await expect(page.getByText("Wrong username or password.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Record" })).toHaveCount(0);
});

test("staff sign in, record a cancellation, and sign out", async ({ page, context }) => {
  test.skip(!USER || !PASSWORD, "set SKYHUB_E2E_STAFF_USER and SKYHUB_E2E_STAFF_PASSWORD");
  // A far-off date nobody books, different each run, so reruns do not depend on earlier ones.
  const date = new Date(Date.now() + (400 + Math.floor(Math.random() * 300)) * 86_400_000).toISOString().slice(0, 10);

  await page.goto("/ops");
  await page.getByLabel("Username").fill(USER!);
  await page.getByLabel("Password").fill(PASSWORD!);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByText(`Signed in as`)).toContainText(USER!);

  // The token is in a cookie the page's scripts cannot read, and it is not sent to the rest of the site.
  const [cookie] = (await context.cookies()).filter((c) => c.name === "skyhub_staff");
  expect(cookie.httpOnly).toBe(true);
  expect(cookie.sameSite).toBe("Strict");
  expect(cookie.path).toBe("/ops");

  // The dashboard lists the tools; each opens on its own address (ADR-0084).
  await page.getByRole("link", { name: /Record a delay or cancellation/ }).click();
  await expect(page).toHaveURL(/\/ops\?tool=record$/);
  const record = page.locator("form").filter({ hasText: "Record a delay or" });
  await record.getByLabel("Flight number").fill("SK501");
  await record.getByLabel(/Scheduled departure date/).first().fill(date);
  await record.getByLabel("Cancelled").check();
  await record.getByLabel(/Reason shown to travellers/).fill("End-to-end test");
  await record.getByRole("button", { name: "Record" }).click();

  await expect(page.getByRole("status")).toContainText(`SK501 on ${date} is recorded as cancelled.`);
  await expect(page.getByRole("status")).toContainText("No active bookings are on this flight.");

  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
});
