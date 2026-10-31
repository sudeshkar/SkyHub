import type { Metadata } from "next";
import Link from "next/link";
import { Suspense } from "react";
import { readAccountSession } from "@/lib/account-session";
import { MyBookings, parseBookingsFilter } from "@/components/account/my-bookings";
import { SignOutEverywhere } from "@/components/account/sign-out-everywhere";
import { getMyAccount, listMyBookings } from "@/lib/api/booking";
import { signOut } from "./actions";
import { SignInForm } from "./sign-in-form";

export const metadata: Metadata = {
  title: "Your account — SkyHub",
  robots: { index: false, follow: false },
};

const PROBLEMS: Record<string, string> = {
  open: "That booking could not be opened. Please try again.",
  wait: "Too many attempts. Please wait a minute and try again.",
  signout: "We could not sign you out everywhere just now, so you are still signed in. Please try again.",
};

type AccountSearchParams = Promise<{ problem?: string; cursor?: string; when?: string | string[] }>;

export default function AccountPage({ searchParams }: { searchParams: AccountSearchParams }) {
  return (
    <div className="mx-auto max-w-3xl px-4 py-10">
      <h1 className="font-display text-3xl">Your account</h1>
      <Suspense fallback={<p aria-busy="true" className="mt-8 text-muted">Loading…</p>}>
        <AccountContent searchParams={searchParams} />
      </Suspense>
    </div>
  );
}

/** Reads the session cookie, so it must render per request (hence the Suspense boundary above). */
async function AccountContent({ searchParams }: { searchParams: AccountSearchParams }) {
  const session = await readAccountSession();
  if (!session) {
    return (
      <>
        <p className="mb-8 mt-2 text-muted">Sign in with your email address.</p>
        <SignInForm />
      </>
    );
  }
  const account = await getMyAccount(session.token);
  if (account.kind === "unauthorized") {
    // The token has expired or was refused: the cookie is no use any more, so show the sign-in form again.
    return (
      <>
        <p role="alert" className="mb-8 mt-2 text-sm text-danger">Your session has ended. Please sign in again.</p>
        <SignInForm />
      </>
    );
  }
  return (
    <>
      <div className="mb-8 mt-2 flex flex-wrap items-center justify-between gap-3">
        <p className="text-muted">
          Signed in as <strong className="text-text">{account.kind === "account" ? account.email : session.email}</strong>
        </p>
        <form action={signOut}>
          <button type="submit" className="rounded-md border border-sand px-4 py-2 text-sm hover:bg-white">
            Sign out
          </button>
        </form>
      </div>
      <p className="mb-8 text-sm">
        <Link href="/account/change-email" className="underline">Change email address</Link>
      </p>
      <MyBookingsSection token={session.token} {...(await searchParams)} />
      <p className="mt-8 text-sm text-muted">
        Booked with a different address? Use <Link href="/manage" className="underline">Manage booking</Link> with its reference and your last name.
      </p>
      <SignOutEverywhere />
    </>
  );
}

async function MyBookingsSection({ token, problem, cursor, when }: { token: string; problem?: string; cursor?: string; when?: string | string[] }) {
  const filter = parseBookingsFilter(when);
  const result = await listMyBookings(token, cursor, filter === "all" ? undefined : filter);
  return (
    <section aria-labelledby="my-bookings">
      <h2 id="my-bookings" className="font-display text-xl">Your bookings</h2>
      {problem && PROBLEMS[problem] ? <p role="alert" className="mt-2 text-sm text-danger">{PROBLEMS[problem]}</p> : null}
      {result.kind === "bookings" ? (
        <MyBookings bookings={result.bookings} nextCursor={result.nextCursor} firstPage={!cursor} when={filter} />
      ) : result.kind === "bad-cursor" ? (
        <p role="alert" className="mt-2 text-sm text-danger">
          That page of bookings could not be found. <Link href="/account" className="underline">Newest bookings</Link>
        </p>
      ) : (
        <p role="alert" className="mt-2 text-sm text-danger">
          {result.kind === "unauthorized" ? "Your session has ended. Please sign in again." : "Your bookings could not be loaded right now. Please try again."}
        </p>
      )}
    </section>
  );
}
