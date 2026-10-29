import type { Metadata } from "next";
import Link from "next/link";
import { Suspense } from "react";
import { ItineraryCard } from "@/components/itinerary-card";
import { airport } from "@/lib/airports";
import { searchItineraries } from "@/lib/api/search";
import { formatSearchDate } from "@/lib/format";
import { parseSearch } from "@/lib/search-params";

export const metadata: Metadata = {
  title: "Flight results — SkyHub",
};

export default function SearchPage(props: PageProps<"/search">) {
  return (
    <div className="mx-auto max-w-5xl px-4 py-10">
      <Suspense fallback={<ResultsSkeleton />}>
        <Results searchParams={props.searchParams} />
      </Suspense>
    </div>
  );
}

async function Results({ searchParams }: Pick<PageProps<"/search">, "searchParams">) {
  const parsed = parseSearch(await searchParams);
  if (!parsed.ok) {
    return <Message title="Check your search" body={parsed.message} />;
  }

  const { origin, destination, date } = parsed.query;
  const result = await searchItineraries(parsed.query);

  return (
    <>
      <header className="mb-8 flex flex-wrap items-end justify-between gap-4 border-b border-sand pb-6">
        <div>
          <h1 className="font-display text-3xl">
            {airport(origin).city} to {airport(destination).city}
          </h1>
          <p className="mt-1 text-muted">
            <span className="font-mono">
              {origin} → {destination}
            </span>{" "}
            · {formatSearchDate(date)}
          </p>
        </div>
        <Link href="/" className="text-copper hover:text-copper-strong">
          Change search
        </Link>
      </header>

      {result.kind === "unavailable" && (
        <Message
          title="We couldn't load flights"
          body="Flight search is not responding right now. Please try again in a moment."
          retryHref={`/search?origin=${origin}&destination=${destination}&date=${date}`}
        />
      )}
      {result.kind === "rejected" && (
        <Message title="Check your search" body="We couldn't search with those details. Please change them and try again." />
      )}
      {result.kind === "ok" && result.itineraries.length === 0 && (
        <Message
          title="No flights on this date"
          body="There are no direct or one-stop flights for this route on the date you chose. Try another date."
        />
      )}
      {result.kind === "ok" && result.itineraries.length > 0 && (
        <section aria-label="Flight options">
          <p className="mb-6 text-sm text-muted" role="status">
            {result.itineraries.length} {result.itineraries.length === 1 ? "option" : "options"}, earliest first
          </p>
          {result.itineraries.map((itinerary) => (
            <ItineraryCard
              key={itinerary.legs.map((leg) => `${leg.flightNumber}@${leg.departureTime}`).join("+")}
              itinerary={itinerary}
            />
          ))}
        </section>
      )}
    </>
  );
}

function Message({ title, body, retryHref }: { title: string; body: string; retryHref?: string }) {
  return (
    <div className="rounded-lg border border-sand bg-white p-8">
      <h2 className="text-xl font-semibold">{title}</h2>
      <p className="mt-2 text-muted">{body}</p>
      <div className="mt-6 flex gap-6">
        {retryHref && (
          <Link href={retryHref} className="font-semibold text-copper hover:text-copper-strong">
            Try again
          </Link>
        )}
        <Link href="/" className="text-copper hover:text-copper-strong">
          New search
        </Link>
      </div>
    </div>
  );
}

function ResultsSkeleton() {
  return (
    <div aria-busy="true" aria-label="Loading flights" className="animate-pulse space-y-8">
      <div className="h-9 w-64 rounded bg-sand" />
      {[0, 1, 2].map((row) => (
        <div key={row} className="flex items-center gap-6 border-b border-sand pb-6">
          <div className="h-8 w-16 rounded bg-sand" />
          <div className="h-2 flex-1 rounded-full bg-sand" />
          <div className="h-8 w-16 rounded bg-sand" />
        </div>
      ))}
    </div>
  );
}
