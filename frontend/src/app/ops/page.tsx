import type { Metadata } from "next";
import Link from "next/link";
import { Suspense } from "react";
import { readStaffSession } from "@/lib/staff-session";
import { DisruptionForm } from "./disruption-form";
import { BoardedPanel } from "./boarded-panel";
import { GatePanel } from "./gate-panel";
import { UndoBoardingForm } from "./undo-boarding-form";
import { WithdrawForm } from "./withdraw-form";
import { LateBoardingForm } from "./late-boarding-form";
import { LoginForm } from "./login-form";
import { ProposalsPanel } from "./proposals-panel";
import { logOut } from "./actions";
import { TOOL_ICONS, type ToolId } from "./tool-icons";

// Staff only: keep it out of search results. It is not linked from the public site.
export const metadata: Metadata = {
  title: "Operations — SkyHub",
  robots: { index: false, follow: false },
};

const ROLE_NAMES = { ADMIN: "Administrator", OPS: "Operations", GATE: "Gate agent" } as const;

type Tool = { id: ToolId; name: string; hint: string; panel: React.ReactNode };

/**
 * The tools, grouped by the job they are for. The dashboard shows each as a card; opening one shows that tool alone, at
 * /ops?tool=<id>, so Back returns to the dashboard and a tool used all day can be bookmarked.
 */
const GROUPS: { id: string; title: string; blurb: string; tools: Tool[] }[] = [
  {
    id: "disruptions",
    title: "Disruptions",
    blurb: "Delays, cancellations and moving the bookings they affect.",
    tools: [
      { id: "record", name: "Record a delay or cancellation", hint: "Tell travellers about a disruption on their booking page.", panel: <DisruptionForm /> },
      { id: "withdraw", name: "Withdraw a disruption", hint: "Take back a delay or cancellation recorded by mistake.", panel: <WithdrawForm /> },
      { id: "move", name: "Move affected bookings", hint: "Review proposals and rebook travellers onto other flights.", panel: <ProposalsPanel /> },
    ],
  },
  {
    id: "boarding",
    title: "Boarding",
    blurb: "The gate, and putting boarding right afterwards.",
    tools: [
      { id: "gate", name: "Gate", hint: "Scan boarding passes with the camera or by typing them.", panel: <GatePanel /> },
      { id: "late", name: "Board a late passenger", hint: "Let someone on after the gate has closed, with a reason.", panel: <LateBoardingForm /> },
      { id: "boarded", name: "Who has boarded", hint: "Checked in, boarded and waiting, flight by flight.", panel: <BoardedPanel /> },
      { id: "undo", name: "Undo a boarding", hint: "Correct a boarding that was scanned by mistake.", panel: <UndoBoardingForm /> },
    ],
  },
];

const ALL_TOOLS = GROUPS.flatMap((group) => group.tools.map((tool) => ({ ...tool, group: group.title })));

export default function OpsPage(props: PageProps<"/ops">) {
  return (
    <div className="mx-auto max-w-6xl px-4 py-6 sm:py-8">
      <Suspense
        fallback={
          <>
            <h1 className="font-display text-3xl">Operations</h1>
            <p aria-busy="true" className="mt-8 text-muted">
              Loading…
            </p>
          </>
        }
      >
        <OpsContent searchParams={props.searchParams} />
      </Suspense>
    </div>
  );
}

/** Reads the session cookie and the chosen tool, so it must render per request (hence the Suspense boundary above). */
async function OpsContent({ searchParams }: Pick<PageProps<"/ops">, "searchParams">) {
  const session = await readStaffSession();
  if (!session) {
    return (
      <div className="mx-auto max-w-md">
        <h1 className="font-display text-3xl">Operations</h1>
        <div className="card mt-6 p-6">
          <p className="mb-6 text-muted">Sign in with your staff account.</p>
          <LoginForm />
        </div>
      </div>
    );
  }

  const header = (
    <header className="card flex flex-wrap items-center justify-between gap-4 p-6">
      <div>
        <p className="text-xs font-semibold uppercase tracking-wide text-muted">SkyHub staff</p>
        <h1 className="font-display text-3xl">Operations</h1>
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <p className="flex items-center gap-2 text-sm text-muted">
          Signed in as <strong className="text-text">{session.username}</strong>
          <span className="rounded-full bg-accent/10 px-2.5 py-0.5 text-xs font-semibold text-accent">{ROLE_NAMES[session.role]}</span>
        </p>
        <form action={logOut}>
          <button type="submit" className="rounded-full border border-line bg-white px-4 py-2 text-sm hover:border-accent hover:text-accent">
            Sign out
          </button>
        </form>
      </div>
    </header>
  );

  // A gate agent works the gate and nothing else (ADR-0060): the operations screens would only be refused.
  if (session.role === "GATE") {
    return (
      <>
        {header}
        <div className="card mx-auto mt-6 max-w-3xl p-6">
          <GatePanel />
        </div>
      </>
    );
  }

  const chosen = String((await searchParams).tool ?? "");
  const tool = ALL_TOOLS.find((t) => t.id === chosen);

  if (tool) {
    const Icon = TOOL_ICONS[tool.id];
    // One slim bar instead of the dashboard's header card, so the tool itself fits on the screen without scrolling.
    return (
      <>
        <h1 className="sr-only">Operations: {tool.name}</h1>
        <div className="card flex flex-wrap items-center justify-between gap-x-4 gap-y-2 px-4 py-2.5">
          <nav aria-label="Breadcrumb" className="flex flex-wrap items-center gap-2 text-sm">
            <Link href="/ops" className="flex items-center gap-1.5 rounded-full border border-line bg-white px-3.5 py-1.5 hover:border-accent hover:text-accent">
              <svg viewBox="0 0 20 20" className="size-4" aria-hidden="true">
                <path d="M16 10H4m5-5-5 5 5 5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
              All tools
            </Link>
            <span className="text-muted">{tool.group}</span>
            <span className="text-muted" aria-hidden="true">
              /
            </span>
            <span className="flex items-center gap-1.5 font-semibold text-ink" aria-current="page">
              <Icon className="size-4 text-accent" />
              {tool.name}
            </span>
          </nav>
          <div className="flex items-center gap-3 text-sm">
            <span className="text-muted">
              <strong className="text-text">{session.username}</strong>
            </span>
            <span className="rounded-full bg-accent/10 px-2.5 py-0.5 text-xs font-semibold text-accent">{ROLE_NAMES[session.role]}</span>
            <form action={logOut}>
              <button type="submit" className="rounded-full border border-line bg-white px-3.5 py-1.5 hover:border-accent hover:text-accent">
                Sign out
              </button>
            </form>
          </div>
        </div>
        <div className="card mt-4 p-5 sm:p-6">{tool.panel}</div>
      </>
    );
  }

  return (
    <>
      {header}
      {GROUPS.map((group) => (
        <section key={group.id} aria-labelledby={`${group.id}-heading`} className="mt-8">
          <div className="mb-4">
            <h2 id={`${group.id}-heading`} className="text-lg font-semibold text-ink">
              {group.title}
            </h2>
            <p className="text-sm text-muted">{group.blurb}</p>
          </div>
          <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {group.tools.map((t) => {
              const Icon = TOOL_ICONS[t.id];
              return (
                <li key={t.id}>
                  <Link
                    href={`/ops?tool=${t.id}`}
                    className="card group flex h-full items-start gap-4 p-5 transition-shadow hover:border-accent/40 hover:shadow-[0_1px_2px_rgb(11_27_51/0.06),0_16px_34px_-14px_rgb(10_80_143/0.4)]"
                  >
                    <span className="flex size-11 shrink-0 items-center justify-center rounded-xl bg-accent/10 text-accent group-hover:bg-accent group-hover:text-white">
                      <Icon className="size-6" />
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block font-semibold text-ink group-hover:text-accent">{t.name}</span>
                      <span className="mt-1 block text-sm text-muted">{t.hint}</span>
                    </span>
                    <svg viewBox="0 0 20 20" className="mt-1 size-5 shrink-0 text-muted group-hover:text-accent" aria-hidden="true">
                      <path d="M7.5 4.5 13 10l-5.5 5.5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
                    </svg>
                  </Link>
                </li>
              );
            })}
          </ul>
        </section>
      ))}
    </>
  );
}
