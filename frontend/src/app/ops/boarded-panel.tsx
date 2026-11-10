"use client";

import { useActionState } from "react";
import { loadFlightBoarding, type FlightBoardingState } from "./actions";

const field = "mt-1 block w-full rounded-md border border-line bg-white px-3 py-2.5";

/** One number with its label, such as "3 checked in". */
function Stat({ value, label, tone = "ink" }: { value: number; label: string; tone?: "ink" | "accent" | "amber" | "muted" }) {
  const colour = { ink: "text-ink", accent: "text-accent", amber: "text-amber", muted: "text-muted" }[tone];
  return (
    <div className="rounded-lg border border-line bg-wash/50 px-4 py-3">
      <span className={`block font-mono text-3xl font-semibold ${colour}`}>{value}</span>{" "}
      <span className="text-sm text-muted">{label}</span>
    </div>
  );
}

/**
 * Who has boarded one flight (ADR-0065), for operations and administrators. It shows references and places on the booking, never names,
 * and counts flights and passengers from one so each row can be typed straight into "Undo a boarding".
 */
export function BoardedPanel() {
  const [state, action, pending] = useActionState<FlightBoardingState, FormData>(loadFlightBoarding, { status: "idle" });
  const typed = state.status === "error" ? state.values : undefined;

  if (state.status === "signed-out") {
    return (
      <p role="alert" className="border-l-4 border-amber-line bg-amber-bg px-4 py-3 text-sm text-text">
        Your session has ended, or your role cannot do this. <a href="/ops" className="underline">Sign in again</a>.
      </p>
    );
  }

  return (
    <section aria-label="Who has boarded" className="space-y-6">
      <form action={action} className="tool-form">
        <h2 className="font-display text-xl">Who has boarded</h2>
        <p className="text-sm text-muted">
          See how many passengers are checked in and who has boarded a flight. Use the numbers in a row to undo a boarding that was a mistake.
        </p>
        <label className="block text-sm font-medium">
          Flight number
          <input
            name="flightNumber"
            defaultValue={typed?.flightNumber ?? ""}
            required
            maxLength={6}
            autoCapitalize="characters"
            autoComplete="off"
            placeholder="SK501"
            className={`${field} font-mono uppercase tracking-widest`}
          />
        </label>
        <label className="block text-sm font-medium">
          Scheduled departure date (UTC)
          <input name="departureDate" type="date" defaultValue={typed?.departureDate ?? ""} required className={field} />
        </label>
        <button type="submit" disabled={pending} className="rounded-md border border-accent px-6 py-3 font-semibold text-accent hover:bg-white disabled:opacity-60">
          {pending ? "Loading…" : "Show boarding"}
        </button>
        {state.status === "error" && (
          <p role="alert" className="text-sm text-danger">
            {state.message}
          </p>
        )}
      </form>

      {state.status === "loaded" && (
        <div className="space-y-4">
          {/* The flight at a glance: three numbers and how far boarding has got. */}
          <div role="status" className="space-y-3">
            <p className="font-semibold">
              {state.flightNumber} on {state.departureDate}
            </p>
            <div className="grid grid-cols-3 gap-3">
              <Stat value={state.checkedIn} label="checked in" />
              <Stat value={state.boarded} label="boarded" tone="accent" />
              <Stat value={state.waiting} label="waiting" tone={state.waiting > 0 ? "amber" : "muted"} />
            </div>
            {state.checkedIn > 0 && (
              <div>
                <div
                  className="h-2 overflow-hidden rounded-full bg-wash"
                  aria-hidden="true"
                >
                  <div className="h-full rounded-full bg-accent" style={{ width: `${Math.round((state.boarded / state.checkedIn) * 100)}%` }} />
                </div>
                <p className="mt-1 text-xs text-muted">
                  {Math.round((state.boarded / state.checkedIn) * 100)}% of checked-in passengers have boarded
                </p>
              </div>
            )}
          </div>
          {state.rows.length === 0 ? (
            <p className="text-sm text-muted">Nobody has boarded this flight yet.</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead>
                  <tr className="border-b border-line">
                    <th className="py-2 pr-4 font-medium">Booking</th>
                    <th className="py-2 pr-4 font-medium">Flight on the booking</th>
                    <th className="py-2 pr-4 font-medium">Passenger on the booking</th>
                    <th className="py-2 pr-4 font-medium">Scanned by</th>
                    <th className="py-2 font-medium">At (UTC)</th>
                  </tr>
                </thead>
                <tbody>
                  {state.rows.map((row) => (
                    <tr key={`${row.locator}-${row.leg}-${row.passenger}`} className="border-b border-line/60">
                      <td className="py-2 pr-4 font-mono">{row.locator}</td>
                      <td className="py-2 pr-4">Flight {row.leg}</td>
                      <td className="py-2 pr-4">Passenger {row.passenger}</td>
                      <td className="py-2 pr-4">{row.by}{row.late && <span className="ml-2 rounded bg-amber-bg px-1.5 py-0.5 text-xs font-semibold">late</span>}</td>
                      <td className="py-2">{row.at.replace("T", " ").replace(/\.\d+Z$|Z$/, "")}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </section>
  );
}
