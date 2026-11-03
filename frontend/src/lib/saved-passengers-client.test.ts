import { describe, expect, it, vi } from "vitest";
import { loadSavedPassengers } from "./saved-passengers-client";

const answer = (body: unknown, ok = true) => vi.fn().mockResolvedValue({ ok, json: async () => body } as Response);

describe("loadSavedPassengers (ADR-0078)", () => {
  it("asks this site for the signed-in traveller's names, with their cookie and never from a cache", async () => {
    const fetcher = answer({ passengers: [] });

    await loadSavedPassengers(fetcher);

    expect(fetcher).toHaveBeenCalledWith("/account/saved-passengers", { cache: "no-store", credentials: "same-origin" });
  });

  it("gives back just the names", async () => {
    const fetcher = answer({ passengers: [{ firstName: "Priya", lastName: "Perera" }, { firstName: "Raj", lastName: "Perera", id: 4 }] });

    expect(await loadSavedPassengers(fetcher)).toEqual([{ firstName: "Priya", lastName: "Perera" }, { firstName: "Raj", lastName: "Perera" }]);
  });

  it.each([
    ["the site says no", () => answer({ passengers: [{ firstName: "Priya", lastName: "Perera" }] }, false)],
    ["the request fails", () => vi.fn().mockRejectedValue(new TypeError("offline"))],
    ["the answer is not JSON", () => vi.fn().mockResolvedValue({ ok: true, json: async () => { throw new SyntaxError("bad"); } } as unknown as Response)],
    ["the answer is the wrong shape", () => answer({ passengers: "Priya" })],
  ])("gives no names, and no error to the booking, when %s", async (_why, make) => {
    expect(await loadSavedPassengers(make())).toEqual([]);
  });

  it("skips an entry that is not two names and never gives more than ten", async () => {
    const many = Array.from({ length: 14 }, (_, i) => ({ firstName: `N${i}`, lastName: "Perera" }));
    const fetcher = answer({ passengers: [{ firstName: "Priya" }, { firstName: 3, lastName: "x" }, null, ...many] });

    const names = await loadSavedPassengers(fetcher);

    expect(names).toHaveLength(10);
    expect(names[0]).toEqual({ firstName: "N0", lastName: "Perera" });
  });
});
