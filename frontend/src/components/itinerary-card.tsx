import Link from "next/link";
import type { Connection, Itinerary } from "@/lib/api/search";
import { airport } from "@/lib/airports";
import { dayOffset, formatDuration, formatMoney, localTime } from "@/lib/format";
import { JourneyBar } from "./journey-bar";

function stopsLabel(stops: number): string {
  if (stops === 0) return "Direct";
  return stops === 1 ? "1 stop" : `${stops} stops`;
}

function TransferNotice({ connection }: { connection: Connection }) {
  const from = airport(connection.arrivalAirport);
  const to = airport(connection.departureAirport);
  return (
    <div role="note" className="border-l-4 border-amber-line bg-amber-bg px-4 py-3 text-sm text-amber">
      <p className="font-semibold">Change of airport in {from.city}</p>
      <p className="mt-1 text-text">
        You land at {from.name} ({from.code}) and depart from {to.name} ({to.code}). Your{" "}
        {formatDuration(connection.layoverMinutes)} connection allows {formatDuration(connection.transferMinutes)} to
        travel between the airports.
      </p>
    </div>
  );
}

export function ItineraryCard({ itinerary, bookHref }: { itinerary: Itinerary; bookHref?: string }) {
  const first = itinerary.legs[0];
  const last = itinerary.legs[itinerary.legs.length - 1];
  const origin = airport(first.origin);
  const destination = airport(last.destination);
  const offset = dayOffset(
    { instant: first.departureTime, timeZone: origin.timeZone },
    { instant: last.arrivalTime, timeZone: destination.timeZone },
  );
  const transfer = itinerary.connections.find((connection) => connection.requiresAirportTransfer);
  const via = itinerary.connections.map((connection) => connection.arrivalAirport).join(", ");

  return (
    <article className="border-b border-sand py-6 first:pt-0">
      <div className="grid grid-cols-[auto_1fr_auto] items-center gap-x-4 gap-y-1 sm:grid-cols-[auto_1fr_auto_auto] sm:gap-x-6">
        <div>
          <p className="font-mono text-2xl font-medium">{localTime(first.departureTime, origin.timeZone)}</p>
          <p className="text-sm text-muted">
            {origin.code} · {origin.city}
          </p>
        </div>

        <div className="space-y-2 text-center">
          <p className="text-sm text-muted">
            {stopsLabel(itinerary.stops)}
            {via && ` via ${via}`} · {formatDuration(itinerary.totalDurationMinutes)}
          </p>
          <JourneyBar itinerary={itinerary} />
        </div>

        <div className="text-right">
          <p className="font-mono text-2xl font-medium">
            {localTime(last.arrivalTime, destination.timeZone)}
            {offset > 0 && (
              <sup className="ms-0.5 text-xs text-copper" aria-label={`plus ${offset} day`}>
                +{offset}
              </sup>
            )}
          </p>
          <p className="text-sm text-muted">
            {destination.code} · {destination.city}
          </p>
        </div>

        <p className="col-span-3 mt-2 text-right sm:col-span-1 sm:mt-0 sm:border-s sm:border-sand sm:ps-6">
          <span className="block text-xs text-muted">from</span>
          <span className="text-xl font-semibold">{formatMoney(itinerary.fareFrom, itinerary.currency)}</span>
          <span className="block text-xs text-muted">per person</span>
          {bookHref && (
            <Link
              href={bookHref}
              aria-label={`Select ${itinerary.legs.map((leg) => leg.flightNumber).join(" and ")}`}
              className="mt-2 inline-block rounded-md bg-copper px-5 py-2 text-sm font-semibold text-white hover:bg-copper-strong"
            >
              Select
            </Link>
          )}
        </p>
      </div>

      {transfer && (
        <div className="mt-4">
          <TransferNotice connection={transfer} />
        </div>
      )}

      <details className="mt-3 text-sm">
        <summary className="cursor-pointer text-copper hover:text-copper-strong">Flight details</summary>
        <ol className="mt-3 space-y-3">
          {itinerary.legs.map((leg, index) => {
            const from = airport(leg.origin);
            const to = airport(leg.destination);
            const connection = itinerary.connections[index];
            return (
              <li key={leg.flightNumber}>
                <p>
                  <span className="font-mono">{leg.flightNumber}</span> · {leg.aircraftType}
                </p>
                <p className="text-muted">
                  {localTime(leg.departureTime, from.timeZone)} {from.name} →{" "}
                  {localTime(leg.arrivalTime, to.timeZone)} {to.name}
                </p>
                {connection && (
                  <p className="mt-2 text-muted">
                    Connection in {airport(connection.arrivalAirport).city}:{" "}
                    {formatDuration(connection.layoverMinutes)}
                  </p>
                )}
              </li>
            );
          })}
        </ol>
      </details>
    </article>
  );
}
