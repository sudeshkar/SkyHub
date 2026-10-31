import type { Metadata } from "next";
import Link from "next/link";
import { Suspense } from "react";
import { readAccountSession } from "@/lib/account-session";
import { ChangeEmailForm } from "../change-email-form";

export const metadata: Metadata = {
  title: "Change email address — SkyHub",
  robots: { index: false, follow: false },
};

export default function ChangeEmailPage() {
  return (
    <div className="mx-auto max-w-3xl px-4 py-10">
      <h1 className="font-display text-3xl">Your account</h1>
      <div className="mt-8">
        <Suspense fallback={<p aria-busy="true" className="text-muted">Loading…</p>}>
          <Content />
        </Suspense>
      </div>
    </div>
  );
}

/** Reads the session cookie, so it must render per request (hence the Suspense boundary above). */
async function Content() {
  if (await readAccountSession()) return <ChangeEmailForm />;
  return (
    <p role="alert" className="max-w-md text-sm text-danger">
      <Link href="/account" className="underline">Sign in</Link> to change your email address.
    </p>
  );
}
