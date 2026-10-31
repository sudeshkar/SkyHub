import type { Metadata } from "next";
import { Suspense } from "react";
import { UndoEmailForm } from "../undo-email-form";

// The token is in the address by design (it is the emailed link), so keep this page out of search results and stop the
// browser from sending the address on as a referrer.
export const metadata: Metadata = {
  title: "Take your account back — SkyHub",
  robots: { index: false, follow: false },
  referrer: "no-referrer",
};

export default function UndoEmailChangePage({ searchParams }: PageProps<"/account/undo-email-change">) {
  return (
    <div className="mx-auto max-w-3xl px-4 py-10">
      <h1 className="font-display text-3xl">Your account</h1>
      <div className="mt-8">
        <Suspense fallback={<p aria-busy="true" className="text-muted">Loading…</p>}>
          <Content searchParams={searchParams} />
        </Suspense>
      </div>
    </div>
  );
}

/** Reads the address's query, so it must render per request (hence the Suspense boundary above). No sign-in is asked for: the account may be lost. */
async function Content({ searchParams }: Pick<PageProps<"/account/undo-email-change">, "searchParams">) {
  const params = await searchParams;
  const token = typeof params.token === "string" ? params.token : "";
  if (!token) {
    return (
      <p role="alert" className="max-w-md text-sm text-danger">
        This page needs the link from the email we sent to your old address. If you cannot find it, contact SkyHub support.
      </p>
    );
  }
  return <UndoEmailForm token={token} />;
}
