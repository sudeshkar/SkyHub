import type { Metadata } from "next";
import Link from "next/link";
import { Suspense } from "react";
import { SetPasswordForm } from "../set-password-form";

// The token is in the address by design (it is the emailed link), so keep this page out of search results and stop the
// browser from sending the address on as a referrer.
export const metadata: Metadata = {
  title: "Choose a password — SkyHub",
  robots: { index: false, follow: false },
  referrer: "no-referrer",
};

export default function SetPasswordPage({ searchParams }: PageProps<"/account/set-password">) {
  return (
    <div className="mx-auto max-w-3xl px-4 py-10">
      <h1 className="font-display text-3xl">Your account</h1>
      <div className="mt-8">
        <Suspense fallback={<p aria-busy="true" className="text-muted">Loading…</p>}>
          <Form searchParams={searchParams} />
        </Suspense>
      </div>
    </div>
  );
}

/** Reads the address's query, so it must render per request (hence the Suspense boundary above). */
async function Form({ searchParams }: Pick<PageProps<"/account/set-password">, "searchParams">) {
  const params = await searchParams;
  const token = typeof params.token === "string" ? params.token : "";
  if (token) return <SetPasswordForm token={token} />;
  return (
    <p role="alert" className="max-w-md text-sm text-danger">
      This page needs the link from your email. <Link href="/account/forgot" className="underline">Ask for a new link</Link>
    </p>
  );
}
