import type { Metadata } from "next";
import { Suspense } from "react";
import { readStaffSession } from "@/lib/staff-session";
import { DisruptionForm } from "./disruption-form";
import { WithdrawForm } from "./withdraw-form";
import { LoginForm } from "./login-form";
import { ProposalsPanel } from "./proposals-panel";
import { logOut } from "./actions";

// Staff only: keep it out of search results. It is not linked from the public site.
export const metadata: Metadata = {
  title: "Operations — SkyHub",
  robots: { index: false, follow: false },
};

export default function OpsPage() {
  return (
    <div className="mx-auto max-w-3xl px-4 py-10">
      <h1 className="font-display text-3xl">Operations</h1>
      <Suspense fallback={<p aria-busy="true" className="mt-8 text-muted">Loading…</p>}>
        <OpsContent />
      </Suspense>
    </div>
  );
}

/** Reads the session cookie, so it must render per request (hence the Suspense boundary above). */
async function OpsContent() {
  const session = await readStaffSession();
  if (!session) {
    return (
      <>
        <p className="mb-8 mt-2 text-muted">Sign in with your staff account.</p>
        <LoginForm />
      </>
    );
  }
  return (
    <>
      <div className="mb-8 mt-2 flex flex-wrap items-center justify-between gap-3">
        <p className="text-muted">
          Signed in as <strong className="text-text">{session.username}</strong> ({session.role === "ADMIN" ? "administrator" : "operations"})
        </p>
        <form action={logOut}>
          <button type="submit" className="rounded-md border border-sand px-4 py-2 text-sm hover:bg-white">
            Sign out
          </button>
        </form>
      </div>
      <DisruptionForm />
      <ProposalsPanel />
      <WithdrawForm />
    </>
  );
}
