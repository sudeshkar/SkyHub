import { cookies } from "next/headers";

/**
 * The staff token lives in an httpOnly cookie that only this server reads (ADR-0018), the same way the manage-booking
 * token does (ADR-0011): scripts on the page cannot see it, it is never in a URL, and SameSite=Strict keeps other
 * sites from triggering requests with it. The path keeps it away from every page except /ops.
 */
export const STAFF_COOKIE = "skyhub_staff";
const COOKIE_PATH = "/ops";
const MAX_SECONDS = 30 * 60;
const USERNAME = /^[a-z][a-z0-9._-]{2,39}$/;

export type StaffRole = "OPS" | "ADMIN" | "GATE";
export type StaffSession = { username: string; role: StaffRole; token: string };

/** Reads the cookie value defensively: anything malformed counts as "no session". */
export function parseStaffSession(value: string | undefined): StaffSession | null {
  if (!value) return null;
  try {
    const parsed: unknown = JSON.parse(value);
    if (typeof parsed !== "object" || parsed === null) return null;
    const { username, role, token } = parsed as Record<string, unknown>;
    if (typeof username !== "string" || !USERNAME.test(username)) return null;
    if (role !== "OPS" && role !== "ADMIN") return null;
    if (typeof token !== "string" || token.length < 20 || token.length > 2_000) return null;
    return { username, role, token };
  } catch {
    return null;
  }
}

/** Seconds the cookie should live: until the token expires, never longer than the token's lifetime. */
export function staffCookieLifetime(expiresAt: string, now: number = Date.now()): number {
  const seconds = Math.floor((Date.parse(expiresAt) - now) / 1000);
  return Number.isFinite(seconds) ? Math.min(MAX_SECONDS, Math.max(1, seconds)) : MAX_SECONDS;
}

export async function readStaffSession(): Promise<StaffSession | null> {
  return parseStaffSession((await cookies()).get(STAFF_COOKIE)?.value);
}

export async function saveStaffSession(session: StaffSession, expiresAt: string): Promise<void> {
  (await cookies()).set(STAFF_COOKIE, JSON.stringify(session), {
    httpOnly: true,
    sameSite: "strict",
    secure: process.env.NODE_ENV === "production",
    path: COOKIE_PATH,
    maxAge: staffCookieLifetime(expiresAt),
  });
}

export async function clearStaffSession(): Promise<void> {
  (await cookies()).set(STAFF_COOKIE, "", { httpOnly: true, sameSite: "strict", path: COOKIE_PATH, maxAge: 0 });
}
