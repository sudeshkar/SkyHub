import { openMyBooking } from "@/app/account/my-bookings-actions";
import type { MyBooking } from "@/lib/api/booking";
import { formatSearchDate } from "@/lib/format";

/** The traveller's bookings, newest first. Opening one is a form post, so no reference or token appears in an address. */
export function MyBookings({ bookings, truncated }: { bookings: MyBooking[]; truncated: boolean }) {
  if (bookings.length === 0) {
    return (
      <p className="mt-2 text-sm text-muted">
        You have no bookings yet. Bookings made with this email address appear here, including ones made before you signed up.
      </p>
    );
  }
  return (
    <>
      <ul className="mt-3 divide-y divide-sand rounded-lg border border-sand bg-white">
        {bookings.map((booking) => (
          <li key={booking.locator} className="flex flex-wrap items-center justify-between gap-3 px-4 py-3">
            <div>
              <p className="font-mono text-lg tracking-wider">{booking.locator}</p>
              <p className="text-sm text-muted">
                {booking.segments.map((s) => `${s.flightNumber} · ${formatSearchDate(s.departureDate)}`).join("  →  ")}
              </p>
              <p className="text-sm text-muted">
                {booking.passengerCount} {booking.passengerCount === 1 ? "passenger" : "passengers"}
                {booking.status === "CANCELLED" ? <strong className="ml-2 text-danger">Cancelled</strong> : null}
              </p>
            </div>
            <form action={openMyBooking.bind(null, booking.locator)}>
              <button
                type="submit"
                aria-label={`Open booking ${booking.locator}`}
                className="rounded-md border border-sand px-4 py-2 text-sm hover:bg-sand/40"
              >
                Open
              </button>
            </form>
          </li>
        ))}
      </ul>
      {truncated ? <p className="mt-2 text-sm text-muted">Showing your newest 50 bookings.</p> : null}
    </>
  );
}
