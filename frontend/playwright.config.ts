import { defineConfig, devices } from "@playwright/test";

/**
 * End-to-end tests drive the real website against the real services and database, so they need the whole stack
 * running first (see "Run it locally" in the root README). They are not part of CI: `npm test` stays fast and needs
 * nothing running. Run them with `npm run e2e`.
 */
export default defineConfig({
  testDir: "./e2e",
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  retries: 0,
  reporter: [["list"]],
  use: {
    baseURL: process.env.SKYHUB_WEB_URL ?? "http://localhost:3000",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
