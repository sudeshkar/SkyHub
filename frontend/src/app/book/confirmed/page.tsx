import type { Metadata } from "next";
import { ConfirmedView } from "@/components/booking/booking-confirmation";

export const metadata: Metadata = {
  title: "Booking confirmed — SkyHub",
};

/**
 * Where the booking steps end. The booking is read from this tab's session storage (see lib/recent-booking.ts), so the
 * booking reference never appears in the address.
 */
export default function ConfirmedPage() {
  return (
    <div className="mx-auto max-w-5xl px-4 py-10">
      <h1 className="mb-8 font-display text-3xl">Complete your booking</h1>
      <ConfirmedView />
    </div>
  );
}
