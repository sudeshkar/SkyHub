import type { Metadata } from "next";
import { Suspense } from "react";
import { ManageView } from "@/components/manage/manage-view";
import { getManagedPnr } from "@/lib/api/booking";
import { readSession } from "@/lib/manage-session";
import { ManageForm } from "./manage-form";

export const metadata: Metadata = {
  title: "Manage booking — SkyHub",
};

export default function ManagePage() {
  return (
    <div className="mx-auto max-w-5xl px-4 py-10">
      <h1 className="font-display text-3xl">Manage your booking</h1>
      <Suspense fallback={<p aria-busy="true" className="mt-8 text-muted">Loading…</p>}>
        <ManageContent />
      </Suspense>
    </div>
  );
}

/** Reads the session cookie, so it must render per request (hence the Suspense boundary above). */
async function ManageContent() {
  const session = await readSession();
  if (!session) return <LookupIntro />;

  const result = await getManagedPnr(session.locator, session.token);
  if (result.kind === "ok") return <ManageView pnr={result.pnr} />;
  if (result.kind === "unavailable") {
    return (
      <p role="alert" className="mt-8 text-danger">
        Booking lookup is not responding right now. Please refresh in a moment.
      </p>
    );
  }
  // Token expired or not valid for this booking: back to the form (a new lookup replaces the cookie).
  return <LookupIntro notice="Your session has ended. Enter your booking details again to continue." />;
}

function LookupIntro({ notice }: { notice?: string }) {
  return (
    <>
      <p className="mb-8 mt-2 text-muted">You need your booking reference and the last name of any passenger.</p>
      <ManageForm notice={notice} />
    </>
  );
}
