import { describe, expect, it } from "vitest";

import { GET } from "./route";

describe("the health route", () => {
  it("says the site is up, with nothing about anything behind it", async () => {
    const response = await GET();

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ status: "UP" });
  });

  it("is never kept by a browser or a cache, so a stopped site is never reported as up", async () => {
    const response = await GET();

    expect(response.headers.get("cache-control")).toBe("no-store");
  });
});
