import { beforeEach, describe, expect, it, vi } from "vitest";
import { clearAccountSession, readAccountSession } from "@/lib/account-session";
import { removeSavedPassenger, saveMyPassenger } from "@/lib/api/booking";
import { rateLimit } from "@/lib/rate-limit";
import { addSavedPassenger, deleteSavedPassenger } from "./saved-passenger-actions";

vi.mock("@/lib/rate-limit", () => ({ POLICIES: { manage: {} }, rateLimit: vi.fn() }));
vi.mock("@/lib/account-session", () => ({ readAccountSession: vi.fn(), clearAccountSession: vi.fn() }));
vi.mock("@/lib/api/booking", () => ({ saveMyPassenger: vi.fn(), removeSavedPassenger: vi.fn() }));
vi.mock("next/navigation", () => ({
  redirect: vi.fn((to: string) => {
    throw new Error(`NEXT_REDIRECT:${to}`);
  }),
}));

const TOKEN = "a".repeat(40);

function form(fields: Record<string, string>): FormData {
  const data = new FormData();
  for (const [key, value] of Object.entries(fields)) data.set(key, value);
  return data;
}

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(rateLimit).mockResolvedValue({ allowed: true });
  vi.mocked(readAccountSession).mockResolvedValue({ email: "priya@example.com", token: TOKEN });
});

describe("addSavedPassenger (ADR-0078)", () => {
  it("saves the trimmed names with the account token and shows the account page again", async () => {
    vi.mocked(saveMyPassenger).mockResolvedValue({ kind: "saved" });

    await expect(addSavedPassenger(form({ firstName: "  Priya ", lastName: " Perera " }))).rejects.toThrow("NEXT_REDIRECT:/account");

    expect(saveMyPassenger).toHaveBeenCalledWith(TOKEN, "Priya", "Perera");
  });

  it("treats a name that was already saved as done", async () => {
    vi.mocked(saveMyPassenger).mockResolvedValue({ kind: "already-saved" });

    await expect(addSavedPassenger(form({ firstName: "Priya", lastName: "Perera" }))).rejects.toThrow("NEXT_REDIRECT:/account");
  });

  it.each([
    ["a missing first name", { firstName: "  ", lastName: "Perera" }],
    ["a missing last name", { firstName: "Priya", lastName: "" }],
    ["a name over 50 characters", { firstName: "x".repeat(51), lastName: "Perera" }],
  ])("refuses %s before calling the service", async (_why, fields) => {
    await expect(addSavedPassenger(form(fields))).rejects.toThrow("NEXT_REDIRECT:/account?problem=passenger-invalid");
    expect(saveMyPassenger).not.toHaveBeenCalled();
  });

  it("says when the ten places are full, when the service refuses the name and when it does not answer", async () => {
    vi.mocked(saveMyPassenger).mockResolvedValueOnce({ kind: "full" });
    await expect(addSavedPassenger(form({ firstName: "Priya", lastName: "Perera" }))).rejects.toThrow("NEXT_REDIRECT:/account?problem=passengers-full");

    vi.mocked(saveMyPassenger).mockResolvedValueOnce({ kind: "rejected", detail: "no" });
    await expect(addSavedPassenger(form({ firstName: "Priya", lastName: "Perera" }))).rejects.toThrow("NEXT_REDIRECT:/account?problem=passenger-invalid");

    vi.mocked(saveMyPassenger).mockResolvedValueOnce({ kind: "unavailable" });
    await expect(addSavedPassenger(form({ firstName: "Priya", lastName: "Perera" }))).rejects.toThrow("NEXT_REDIRECT:/account?problem=passenger-failed");
  });

  it("sends a signed-out person, or one whose token the service refused, back to the sign-in page", async () => {
    vi.mocked(readAccountSession).mockResolvedValue(null);
    await expect(addSavedPassenger(form({ firstName: "Priya", lastName: "Perera" }))).rejects.toThrow("NEXT_REDIRECT:/account");
    expect(saveMyPassenger).not.toHaveBeenCalled();

    vi.mocked(readAccountSession).mockResolvedValue({ email: "priya@example.com", token: TOKEN });
    vi.mocked(saveMyPassenger).mockResolvedValue({ kind: "unauthorized" });
    await expect(addSavedPassenger(form({ firstName: "Priya", lastName: "Perera" }))).rejects.toThrow("NEXT_REDIRECT:/account");
    expect(clearAccountSession).toHaveBeenCalled();
  });

  it("is rate limited, and saves nothing when it is", async () => {
    vi.mocked(rateLimit).mockResolvedValue({ allowed: false, retryAfterSeconds: 5 });

    await expect(addSavedPassenger(form({ firstName: "Priya", lastName: "Perera" }))).rejects.toThrow("NEXT_REDIRECT:/account?problem=wait");
    expect(saveMyPassenger).not.toHaveBeenCalled();
  });
});

describe("deleteSavedPassenger (ADR-0078)", () => {
  it("removes the name with the account token", async () => {
    vi.mocked(removeSavedPassenger).mockResolvedValue({ kind: "removed" });

    await expect(deleteSavedPassenger(form({ id: "12" }))).rejects.toThrow("NEXT_REDIRECT:/account");

    expect(removeSavedPassenger).toHaveBeenCalledWith(TOKEN, 12);
  });

  it("treats a name that is already gone as done", async () => {
    vi.mocked(removeSavedPassenger).mockResolvedValue({ kind: "not-found" });

    await expect(deleteSavedPassenger(form({ id: "12" }))).rejects.toThrow("NEXT_REDIRECT:/account");
  });

  it.each([["abc"], ["-3"], ["0"], ["1.5"], [""]])("does not call the service for an id that is not a whole positive number (%s)", async (id) => {
    await expect(deleteSavedPassenger(form({ id }))).rejects.toThrow("NEXT_REDIRECT:/account?problem=passenger-failed");
    expect(removeSavedPassenger).not.toHaveBeenCalled();
  });

  it("says so when the service does not answer, and sends a signed-out person to sign in", async () => {
    vi.mocked(removeSavedPassenger).mockResolvedValue({ kind: "unavailable" });
    await expect(deleteSavedPassenger(form({ id: "12" }))).rejects.toThrow("NEXT_REDIRECT:/account?problem=passenger-failed");

    vi.mocked(readAccountSession).mockResolvedValue(null);
    await expect(deleteSavedPassenger(form({ id: "12" }))).rejects.toThrow("NEXT_REDIRECT:/account");
  });
});
