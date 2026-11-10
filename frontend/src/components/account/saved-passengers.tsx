import { addSavedPassenger, deleteSavedPassenger } from "@/app/account/saved-passenger-actions";
import type { SavedPassengersOutcome } from "@/lib/api/booking";

const PROBLEMS: Record<string, string> = {
  "passenger-invalid": "Enter a first and a last name of 1 to 50 characters each.",
  "passengers-full": "All 10 places are used. Remove a name first.",
  "passenger-failed": "That could not be done just now. Please try again.",
};

const field = "mt-1 block w-full rounded-md border border-line bg-white px-3 py-2";

/**
 * The names a traveller keeps to fill a booking with (ADR-0078). Names only: it says so, because a person deciding what to keep here
 * should know that no date of birth or passport is held. Forms post to server actions, so no name is ever in an address, and a name is
 * removed by its number.
 */
export function SavedPassengers({ result, problem }: { result: SavedPassengersOutcome; problem?: string }) {
  return (
    <section aria-labelledby="saved-passengers" className="mt-8 border-t border-line pt-6">
      <h2 id="saved-passengers" className="font-display text-xl">Saved passengers</h2>
      <p className="mt-2 max-w-prose text-sm text-muted">
        Save the people you travel with, and fill in a booking for them in one click. We keep only the names, never a date of birth,
        passport or contact detail.
      </p>
      {problem && PROBLEMS[problem] ? <p role="alert" className="mt-2 text-sm text-danger">{PROBLEMS[problem]}</p> : null}
      {result.kind === "list" ? <List passengers={result.passengers} limit={result.limit} /> : (
        <p role="alert" className="mt-2 text-sm text-danger">
          {result.kind === "unauthorized" ? "Your session has ended. Please sign in again." : "Your saved passengers could not be loaded right now. Please try again."}
        </p>
      )}
    </section>
  );
}

function List({ passengers, limit }: { passengers: { id: number; firstName: string; lastName: string }[]; limit: number }) {
  const full = passengers.length >= limit;
  return (
    <>
      {passengers.length === 0 ? (
        <p className="mt-3 text-sm text-muted">No saved passengers yet.</p>
      ) : (
        <>
          <p className="mt-3 text-sm text-muted">{passengers.length} of {limit} places used.</p>
          <ul className="mt-2 max-w-md divide-y divide-line border border-line bg-white">
            {passengers.map((p) => (
              <li key={p.id} className="flex items-center justify-between gap-3 px-4 py-2">
                <span>{p.firstName} {p.lastName}</span>
                <form action={deleteSavedPassenger}>
                  <input type="hidden" name="id" value={p.id} />
                  <button type="submit" aria-label={`Remove ${p.firstName} ${p.lastName}`} className="rounded-md border border-line px-3 py-1 text-sm hover:bg-wash">
                    Remove
                  </button>
                </form>
              </li>
            ))}
          </ul>
        </>
      )}
      {full ? (
        <p className="mt-3 text-sm text-muted">All {limit} places are used. Remove a name to save another.</p>
      ) : (
        <form action={addSavedPassenger} className="mt-4 grid max-w-md gap-3 sm:grid-cols-2">
          <label className="block text-sm font-medium">
            First name
            <input name="firstName" required maxLength={50} autoComplete="off" className={field} />
          </label>
          <label className="block text-sm font-medium">
            Last name
            <input name="lastName" required maxLength={50} autoComplete="off" className={field} />
          </label>
          <button type="submit" className="rounded-full border border-accent px-5 py-2 font-semibold text-accent hover:bg-white sm:col-span-2 sm:justify-self-start">
            Save passenger
          </button>
        </form>
      )}
    </>
  );
}
