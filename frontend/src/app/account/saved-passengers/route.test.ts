import { beforeEach, describe, expect, it, vi } from "vitest";
import { readAccountSession } from "@/lib/account-session";
import { listSavedPassengers } from "@/lib/api/booking";
import { rateLimit } from "@/lib/rate-limit";
import { GET } from "./route";

vi.mock("@/lib/rate-limit", () => ({ POLICIES: { manage: {} }, rateLimit: vi.fn() }));
vi.mock("@/lib/account-session", () => ({ readAccountSession: vi.fn() }));
vi.mock("@/lib/api/booking", () => ({ listSavedPassengers: vi.fn() }));

const TOKEN = "a".repeat(40);

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(rateLimit).mockResolvedValue({ allowed: true });
  vi.mocked(readAccountSession).mockResolvedValue({ email: "priya@example.com", token: TOKEN });
});

describe("GET /account/saved-passengers (ADR-0078)", () => {
  it("gives the signed-in traveller's names and nothing else about them, and is never cached", async () => {
    vi.mocked(listSavedPassengers).mockResolvedValue({
      kind: "list", limit: 10, passengers: [{ id: 3, firstName: "Priya", lastName: "Perera" }],
    });

    const response = await GET();

    expect(listSavedPassengers).toHaveBeenCalledWith(TOKEN);
    expect(response.status).toBe(200);
    expect(response.headers.get("Cache-Control")).toContain("no-store");
    expect(await response.json()).toEqual({ passengers: [{ firstName: "Priya", lastName: "Perera" }] });   // no ids, no address
  });

  it("answers the same empty list for someone who is not signed in, so it says nothing about who is", async () => {
    vi.mocked(readAccountSession).mockResolvedValue(null);

    const response = await GET();

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ passengers: [] });
    expect(listSavedPassengers).not.toHaveBeenCalled();
  });

  it.each([["unauthorized"], ["unavailable"]] as const)("answers an empty list, not an error, when the booking service says %s", async (kind) => {
    vi.mocked(listSavedPassengers).mockResolvedValue({ kind });

    const response = await GET();

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ passengers: [] });
  });

  it("is rate limited, and asks nothing when it is", async () => {
    vi.mocked(rateLimit).mockResolvedValue({ allowed: false, retryAfterSeconds: 9 });

    const response = await GET();

    expect(response.status).toBe(429);
    expect(response.headers.get("Retry-After")).toBe("9");
    expect(listSavedPassengers).not.toHaveBeenCalled();
  });
});
