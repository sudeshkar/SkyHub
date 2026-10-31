import { beforeEach, describe, expect, it, vi } from "vitest";
import { clearAccountSession } from "@/lib/account-session";
import { undoAccountEmailChange } from "@/lib/api/booking";
import { rateLimit } from "@/lib/rate-limit";
import { undoEmailChange } from "./email-actions";

vi.mock("@/lib/rate-limit", () => ({ POLICIES: { accountSignIn: {}, manage: {} }, rateLimit: vi.fn() }));
vi.mock("@/lib/account-session", () => ({ readAccountSession: vi.fn(), clearAccountSession: vi.fn() }));
vi.mock("@/lib/api/booking", () => ({
  requestAccountEmailChange: vi.fn(),
  confirmAccountEmailChange: vi.fn(),
  undoAccountEmailChange: vi.fn(),
}));

function form(fields: Record<string, string>): FormData {
  const data = new FormData();
  for (const [key, value] of Object.entries(fields)) data.set(key, value);
  return data;
}

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(rateLimit).mockResolvedValue({ allowed: true });
});

describe("undoEmailChange (ADR-0054)", () => {
  it("takes the account back with the emailed link, signed in or not", async () => {
    vi.mocked(undoAccountEmailChange).mockResolvedValue({ kind: "undone" });

    expect(await undoEmailChange({ status: "idle" }, form({ token: "abc" }))).toEqual({ status: "done" });
    expect(undoAccountEmailChange).toHaveBeenCalledWith("abc");
  });

  it("drops any session cookie of this browser, since every session of the account has just ended", async () => {
    vi.mocked(undoAccountEmailChange).mockResolvedValue({ kind: "undone" });

    await undoEmailChange({ status: "idle" }, form({ token: "abc" }));

    expect(clearAccountSession).toHaveBeenCalledTimes(1);
  });

  it("says the link has stopped working for a wrong, used or expired link, and keeps the cookie", async () => {
    vi.mocked(undoAccountEmailChange).mockResolvedValue({ kind: "invalid-link" });

    expect(await undoEmailChange({ status: "idle" }, form({ token: "old" }))).toEqual({ status: "link-invalid" });
    expect(clearAccountSession).not.toHaveBeenCalled();
  });

  it("does not call the service for an empty or absurdly long token", async () => {
    expect(await undoEmailChange({ status: "idle" }, form({ token: "" }))).toEqual({ status: "link-invalid" });
    expect(await undoEmailChange({ status: "idle" }, form({ token: "x".repeat(101) }))).toEqual({ status: "link-invalid" });
    expect(await undoEmailChange({ status: "idle" }, form({}))).toEqual({ status: "link-invalid" });
    expect(undoAccountEmailChange).not.toHaveBeenCalled();
  });

  it("limits attempts, because a token is being checked", async () => {
    vi.mocked(rateLimit).mockResolvedValue({ allowed: false, retryAfterSeconds: 120 });

    expect(await undoEmailChange({ status: "idle" }, form({ token: "abc" }))).toEqual({
      status: "error",
      message: expect.stringContaining("2 minutes"),
    });
    expect(undoAccountEmailChange).not.toHaveBeenCalled();
  });

  it("says so when the booking service does not answer, and does not claim anything was undone", async () => {
    vi.mocked(undoAccountEmailChange).mockResolvedValue({ kind: "unavailable" });

    expect(await undoEmailChange({ status: "idle" }, form({ token: "abc" }))).toMatchObject({ status: "error" });
    expect(clearAccountSession).not.toHaveBeenCalled();
  });
});
