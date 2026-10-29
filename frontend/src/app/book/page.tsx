import type { Metadata } from "next";
import Link from "next/link";
import { Suspense } from "react";
import { BookingWizard } from "@/components/booking/booking-wizard";
import { getSeatMap } from "@/lib/api/booking";
import { parseBooking } from "@/lib/booking-params";

export const metadata: Metadata = {
  title: "Choose seats — SkyHub",
};

export default function BookPage(props: PageProps<"/book">) {
  return (
    <div className="mx-auto max-w-5xl px-4 py-10">
      <h1 className="mb-8 font-display text-3xl">Complete your booking</h1>
      <Suspense fallback={<p aria-busy="true">Loading seat maps…</p>}>
        <Booking searchParams={props.searchParams} />
      </Suspense>
    </div>
  );
}

async function Booking({ searchParams }: Pick<PageProps<"/book">, "searchParams">) {
  const parsed = parseBooking(await searchParams);
  if (!parsed.ok) {
    return <Problem text="This booking link is not valid. Please search again and choose a flight." />;
  }
  const { segments, passengers } = parsed.selection;

  // Every segment's seat map, fetched in parallel.
  const results = await Promise.all(segments.map(getSeatMap));
  if (results.includes("not-found")) {
    return <Problem text="One of these flights doesn't operate on that date. Please search again." />;
  }
  const seatMaps = results.filter((map) => typeof map !== "string");
  if (seatMaps.length !== segments.length) {
    return <Problem text="We couldn't load the seat maps right now. Please try again in a moment." />;
  }

  return <BookingWizard segments={segments} seatMaps={seatMaps} passengerCount={passengers} />;
}

function Problem({ text }: { text: string }) {
  return (
    <div className="rounded-lg border border-sand bg-white p-8">
      <p>{text}</p>
      <Link href="/" className="mt-4 inline-block text-copper hover:text-copper-strong">
        New search
      </Link>
    </div>
  );
}
