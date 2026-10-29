import { cookies } from "next/headers";

/**
 * The manage-booking token lives in an httpOnly cookie that only this server reads. Scripts on the page
 * cannot see it, it is never in a URL, and SameSite=Strict keeps other sites from triggering requests with it.
 */
export const SESSION_COOKIE = "skyhub_manage";
const COOKIE_PATH = "/manage";
const LOCATOR = /^[A-Z0-9]{6}$/;
const MAX_SECONDS = 15 * 60;

export type ManageSession = { locator: string; token: string };

/** Reads the cookie value defensively: anything malformed counts as "no session". */
export function parseSession(value: string | undefined): ManageSession | null {
  if (!value) return null;
  try {
    const parsed: unknown = JSON.parse(value);
    if (typeof parsed !== "object" || parsed === null) return null;
    const { locator, token } = parsed as Record<string, unknown>;
    if (typeof locator !== "string" || !LOCATOR.test(locator)) return null;
    if (typeof token !== "string" || token.length < 20 || token.length > 2_000) return null;
    return { locator, token };
  } catch {
    return null;
  }
}

/** Seconds the cookie should live: until the token expires, never longer than the token's lifetime. */
export function cookieLifetime(expiresAt: string, now: number = Date.now()): number {
  const seconds = Math.floor((Date.parse(expiresAt) - now) / 1000);
  return Number.isFinite(seconds) ? Math.min(MAX_SECONDS, Math.max(1, seconds)) : MAX_SECONDS;
}

export async function readSession(): Promise<ManageSession | null> {
  return parseSession((await cookies()).get(SESSION_COOKIE)?.value);
}

export async function saveSession(session: ManageSession, expiresAt: string): Promise<void> {
  (await cookies()).set(SESSION_COOKIE, JSON.stringify(session), {
    httpOnly: true,
    sameSite: "strict",
    secure: process.env.NODE_ENV === "production",
    path: COOKIE_PATH,
    maxAge: cookieLifetime(expiresAt),
  });
}

export async function clearSession(): Promise<void> {
  (await cookies()).set(SESSION_COOKIE, "", { httpOnly: true, sameSite: "strict", path: COOKIE_PATH, maxAge: 0 });
}
