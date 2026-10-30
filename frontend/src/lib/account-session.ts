import { cookies } from "next/headers";

/**
 * The account token lives in an httpOnly cookie that only this server reads (ADR-0033), the way the staff and manage tokens do:
 * scripts on the page cannot see it, it is never in a URL, and SameSite=Strict keeps other sites from triggering requests
 * with it. The path keeps it away from every page except /account.
 */
export const ACCOUNT_COOKIE = "skyhub_account";
const COOKIE_PATH = "/account";
const MAX_SECONDS = 30 * 60;

export type AccountSession = { email: string; token: string };

/** Reads the cookie value defensively: anything malformed counts as "no session". */
export function parseAccountSession(value: string | undefined): AccountSession | null {
  if (!value) return null;
  try {
    const parsed: unknown = JSON.parse(value);
    if (typeof parsed !== "object" || parsed === null) return null;
    const { email, token } = parsed as Record<string, unknown>;
    if (typeof email !== "string" || !email.includes("@") || email.length > 254) return null;
    if (typeof token !== "string" || token.length < 20 || token.length > 2_000) return null;
    return { email, token };
  } catch {
    return null;
  }
}

/** Seconds the cookie should live: until the token expires, never longer than the token's lifetime. */
export function accountCookieLifetime(expiresAt: string, now: number = Date.now()): number {
  const seconds = Math.floor((Date.parse(expiresAt) - now) / 1000);
  return Number.isFinite(seconds) ? Math.min(MAX_SECONDS, Math.max(1, seconds)) : MAX_SECONDS;
}

export async function readAccountSession(): Promise<AccountSession | null> {
  return parseAccountSession((await cookies()).get(ACCOUNT_COOKIE)?.value);
}

export async function saveAccountSession(session: AccountSession, expiresAt: string): Promise<void> {
  (await cookies()).set(ACCOUNT_COOKIE, JSON.stringify(session), {
    httpOnly: true,
    sameSite: "strict",
    secure: process.env.NODE_ENV === "production",
    path: COOKIE_PATH,
    maxAge: accountCookieLifetime(expiresAt),
  });
}

export async function clearAccountSession(): Promise<void> {
  (await cookies()).set(ACCOUNT_COOKIE, "", { httpOnly: true, sameSite: "strict", path: COOKIE_PATH, maxAge: 0 });
}
