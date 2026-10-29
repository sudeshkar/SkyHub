import { beforeEach, describe, expect, it, vi } from "vitest";
import { getPnr } from "@/lib/api/booking";
import { lookupBooking } from "./actions";

vi.mock("@/lib/api/booking", () => ({ getPnr: vi.fn() }));

function form(locator: string, lastName: string): FormData {
  const data = new FormData();
  data.set("locator", locator);
  data.set("lastName", lastName);
  return data;
}

describe("lookupBooking", () => {
  beforeEach(() => vi.mocked(getPnr).mockReset());

  it("normalises the reference before looking it up", async () => {
    vi.mocked(getPnr).mockResolvedValue("not-found");

    await lookupBooking({ status: "idle" }, form(" yufhbd ", " Perera "));

    expect(getPnr).toHaveBeenCalledWith("YUFHBD", "Perera");
  });

  it("gives the same message for an unknown reference and a wrong name", async () => {
    vi.mocked(getPnr).mockResolvedValue("not-found");

    const result = await lookupBooking({ status: "idle" }, form("YUFHBD", "Silva"));

    expect(result).toEqual({
      status: "error",
      message: "We couldn't find a booking with that reference and last name.",
      locator: "YUFHBD",
    });
  });

  it("rejects a malformed reference without calling the API", async () => {
    const result = await lookupBooking({ status: "idle" }, form("ABC", "Perera"));

    expect(result.status).toBe("error");
    expect(getPnr).not.toHaveBeenCalled();
  });
});
