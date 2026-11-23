import { expect, test } from "@playwright/test";

/**
 * The chat against the real website, the real assistant and its model (ADR-0095). Needs the website started with
 * SKYHUB_ASSISTANT_API_TOKEN and the traveller assistant running with the same token and a GROQ_API_KEY, so it is
 * skipped unless SKYHUB_E2E_ASSISTANT=1. Each run spends a little of the model's free tier.
 */
const ASSISTANT = process.env.SKYHUB_E2E_ASSISTANT;

test.describe("the assistant chat", () => {
  test.skip(!ASSISTANT, "set SKYHUB_E2E_ASSISTANT=1 with the traveller assistant running");

  test("answers a question from the help centre and names the article", async ({ page }) => {
    await page.goto("/");
    await page.getByRole("button", { name: "Ask SkyHub" }).click();
    const panel = page.getByRole("region", { name: "SkyHub assistant" });

    await panel.getByLabel("Your message").fill("When can I check in online?");
    await panel.getByRole("button", { name: "Send" }).click();

    await expect(panel.getByText(/24 hours/)).toBeVisible({ timeout: 60_000 });
    await expect(panel.getByText("From the help centre: Checking in online")).toBeVisible();
  });

  test("asks the traveller to open their booking before answering about it", async ({ page }) => {
    await page.goto("/");
    await page.getByRole("button", { name: "Ask SkyHub" }).click();
    const panel = page.getByRole("region", { name: "SkyHub assistant" });

    await panel.getByLabel("Your message").fill("Am I checked in for my flight?");
    await panel.getByRole("button", { name: "Send" }).click();

    await expect(panel.getByText(/Manage booking page/)).toBeVisible({ timeout: 60_000 });
  });
});
