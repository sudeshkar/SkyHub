import type { Metadata } from "next";
import Link from "next/link";
import { Suspense } from "react";
import { readAccountSession } from "@/lib/account-session";
import { getMyAccount } from "@/lib/api/booking";
import { signOut } from "./actions";
import { SignInForm } from "./sign-in-form";

export const metadata: Metadata = {
  title: "Your account — SkyHub",
  robots: { index: false, follow: false },
};

export default function AccountPage() {
  return (
    <div className="mx-auto max-w-3xl px-4 py-10">
      <h1 className="font-display text-3xl">Your account</h1>
      <Suspense fallback={<p aria-busy="true" className="mt-8 text-muted">Loading…</p>}>
        <AccountContent />
      </Suspense>
    </div>
  );
}

/** Reads the session cookie, so it must render per request (hence the Suspense boundary above). */
async function AccountContent() {
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
      <p className="text-sm text-muted">
        To look after a booking, use <Link href="/manage" className="underline">Manage booking</Link> with its reference and your last name.
      </p>
    </>
  );
}
