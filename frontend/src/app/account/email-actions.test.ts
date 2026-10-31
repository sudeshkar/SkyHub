import { beforeEach, describe, expect, it, vi } from "vitest";
import { clearAccountSession, readAccountSession } from "@/lib/account-session";
import { confirmAccountEmailChange, requestAccountEmailChange } from "@/lib/api/booking";
import { rateLimit } from "@/lib/rate-limit";
import { confirmEmailChange, requestEmailChange } from "./email-actions";

vi.mock("@/lib/rate-limit", () => ({ POLICIES: { accountSignIn: {}, manage: {} }, rateLimit: vi.fn() }));
vi.mock("@/lib/account-session", () => ({ readAccountSession: vi.fn(), clearAccountSession: vi.fn() }));
vi.mock("@/lib/api/booking", () => ({ requestAccountEmailChange: vi.fn(), confirmAccountEmailChange: vi.fn() }));

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

describe("requestEmailChange", () => {
  const good = { newEmail: " Priya.New@Example.com ", password: "a long enough passphrase" };

  it("asks with the account token and the password, and says only that a link was sent if the address can be used", async () => {
    vi.mocked(requestAccountEmailChange).mockResolvedValue({ kind: "sent" });

    const result = await requestEmailChange({ status: "idle" }, form(good));

    expect(requestAccountEmailChange).toHaveBeenCalledWith(TOKEN, "priya.new@example.com", "a long enough passphrase");
    expect(result).toEqual({ status: "sent", email: "priya.new@example.com" });
  });

  it("does not call the service for something that cannot be an address or an empty password", async () => {
    expect(await requestEmailChange({ status: "idle" }, form({ ...good, newEmail: "nope" }))).toMatchObject({
      status: "error", message: expect.stringContaining("valid email"), email: "nope",
    });
    expect(await requestEmailChange({ status: "idle" }, form({ ...good, password: "" }))).toMatchObject({
      status: "error", message: expect.stringContaining("password"),
    });
    expect(requestAccountEmailChange).not.toHaveBeenCalled();
  });

  it("asks a person who is not signed in to sign in, without calling the service", async () => {
    vi.mocked(readAccountSession).mockResolvedValue(null);

    expect(await requestEmailChange({ status: "idle" }, form(good))).toEqual({ status: "signed-out" });
    expect(requestAccountEmailChange).not.toHaveBeenCalled();
  });

  it("says the password was not accepted without ending the session, and keeps the address typed", async () => {
    vi.mocked(requestAccountEmailChange).mockResolvedValue({ kind: "password-refused" });

    const result = await requestEmailChange({ status: "idle" }, form(good));

    expect(result).toMatchObject({ status: "error", message: expect.stringContaining("password"), email: "priya.new@example.com" });
    expect(clearAccountSession).not.toHaveBeenCalled();
  });

  it("drops a session the service has stopped accepting, shows the service's reason, and says when it is unreachable", async () => {
    vi.mocked(requestAccountEmailChange).mockResolvedValueOnce({ kind: "unauthorized" });
    expect(await requestEmailChange({ status: "idle" }, form(good))).toEqual({ status: "signed-out" });
    expect(clearAccountSession).toHaveBeenCalled();

    vi.mocked(requestAccountEmailChange).mockResolvedValueOnce({ kind: "rejected", detail: "That is already your address" });
    expect(await requestEmailChange({ status: "idle" }, form(good))).toMatchObject({ status: "error", message: "That is already your address" });

    vi.mocked(requestAccountEmailChange).mockResolvedValueOnce({ kind: "unavailable" });
    expect(await requestEmailChange({ status: "idle" }, form(good))).toMatchObject({ status: "error", message: expect.stringContaining("not responding") });
  });

  it("stops password guessing before it reaches the service", async () => {
    vi.mocked(rateLimit).mockResolvedValue({ allowed: false, retryAfterSeconds: 600 });

    expect(await requestEmailChange({ status: "idle" }, form(good))).toMatchObject({
      status: "error", message: expect.stringContaining("10 minutes"),
    });
    expect(requestAccountEmailChange).not.toHaveBeenCalled();
  });
});

describe("confirmEmailChange", () => {
  it("spends the emailed link for the signed-in account", async () => {
    vi.mocked(confirmAccountEmailChange).mockResolvedValue({ kind: "changed" });

    expect(await confirmEmailChange({ status: "idle" }, form({ token: "abc" }))).toEqual({ status: "done" });
    expect(confirmAccountEmailChange).toHaveBeenCalledWith(TOKEN, "abc");
  });

  it("asks a person who is not signed in to sign in first, without spending the link", async () => {
    vi.mocked(readAccountSession).mockResolvedValue(null);

    expect(await confirmEmailChange({ status: "idle" }, form({ token: "abc" }))).toEqual({ status: "signed-out" });
    expect(confirmAccountEmailChange).not.toHaveBeenCalled();
  });

  it("says the link has stopped working for a wrong, used or expired link", async () => {
    vi.mocked(confirmAccountEmailChange).mockResolvedValue({ kind: "invalid-link" });

    expect(await confirmEmailChange({ status: "idle" }, form({ token: "old" }))).toEqual({ status: "link-invalid" });
  });

  it("drops a session the service has stopped accepting, and says when it is unreachable", async () => {
    vi.mocked(confirmAccountEmailChange).mockResolvedValueOnce({ kind: "unauthorized" });
    expect(await confirmEmailChange({ status: "idle" }, form({ token: "abc" }))).toEqual({ status: "signed-out" });
    expect(clearAccountSession).toHaveBeenCalled();

    vi.mocked(confirmAccountEmailChange).mockResolvedValueOnce({ kind: "unavailable" });
    expect(await confirmEmailChange({ status: "idle" }, form({ token: "abc" }))).toMatchObject({ status: "error", message: expect.stringContaining("not responding") });
  });

  it("does not call the service for a missing or enormous token, and slows a flood", async () => {
    expect(await confirmEmailChange({ status: "idle" }, form({ token: "" }))).toEqual({ status: "link-invalid" });
    expect(await confirmEmailChange({ status: "idle" }, form({ token: "x".repeat(101) }))).toEqual({ status: "link-invalid" });
    expect(confirmAccountEmailChange).not.toHaveBeenCalled();

    vi.mocked(rateLimit).mockResolvedValue({ allowed: false, retryAfterSeconds: 60 });
    expect((await confirmEmailChange({ status: "idle" }, form({ token: "abc" }))).status).toBe("error");
    expect(confirmAccountEmailChange).not.toHaveBeenCalled();
  });
});
