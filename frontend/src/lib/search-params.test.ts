import { describe, expect, it } from "vitest";
import { parseSearch } from "./search-params";

describe("parseSearch", () => {
  it("accepts a complete search and normalises case", () => {
    expect(parseSearch({ origin: "cmb", destination: "JFK", date: "2026-11-02" })).toEqual({
      ok: true,
      query: { origin: "CMB", destination: "JFK", date: "2026-11-02" },
    });
  });

  it("rejects a missing airport", () => {
    expect(parseSearch({ origin: "CMB", date: "2026-11-02" })).toMatchObject({ ok: false });
  });

  it("rejects identical airports", () => {
    expect(parseSearch({ origin: "CMB", destination: "CMB", date: "2026-11-02" })).toEqual({
      ok: false,
      message: "Departure and arrival airports must be different.",
    });
  });

  it("rejects a malformed date", () => {
    expect(parseSearch({ origin: "CMB", destination: "JFK", date: "02/11/2026" })).toMatchObject({ ok: false });
  });

  it("uses the first value when a parameter repeats", () => {
    expect(parseSearch({ origin: ["CMB", "BOM"], destination: "JFK", date: "2026-11-02" })).toMatchObject({
      ok: true,
      query: { origin: "CMB" },
    });
  });
});
