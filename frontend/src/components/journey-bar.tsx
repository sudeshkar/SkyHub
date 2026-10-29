import type { Itinerary } from "@/lib/api/search";

type Segment = { kind: "flight" | "wait" | "transfer"; minutes: number };

function minutesBetween(fromIso: string, toIso: string): number {
  return (Date.parse(toIso) - Date.parse(fromIso)) / 60_000;
}

export function segmentsOf(itinerary: Itinerary): Segment[] {
  const segments: Segment[] = [];
  itinerary.legs.forEach((leg, index) => {
    segments.push({ kind: "flight", minutes: minutesBetween(leg.departureTime, leg.arrivalTime) });
    const connection = itinerary.connections[index];
    if (connection) {
      if (connection.requiresAirportTransfer) {
        segments.push({ kind: "transfer", minutes: connection.transferMinutes });
      }
      segments.push({ kind: "wait", minutes: connection.layoverMinutes - connection.transferMinutes });
    }
  });
  return segments.filter((segment) => segment.minutes > 0);
}

const STYLE: Record<Segment["kind"], string> = {
  flight: "bg-ink",
  wait: "bg-sand",
  transfer: "bg-amber-line [background-image:repeating-linear-gradient(135deg,transparent_0_4px,rgb(255_255_255/0.55)_4px_8px)]",
};

/** Time-to-scale picture of the journey. Decorative: the card states the same facts in text. */
export function JourneyBar({ itinerary }: { itinerary: Itinerary }) {
  const segments = segmentsOf(itinerary);
  const total = segments.reduce((sum, segment) => sum + segment.minutes, 0);
  return (
    <div aria-hidden="true" className="flex h-2 w-full gap-0.5 overflow-hidden rounded-full">
      {segments.map((segment, index) => (
        <span
          key={index}
          data-kind={segment.kind}
          className={STYLE[segment.kind]}
          style={{ width: `${(segment.minutes / total) * 100}%` }}
        />
      ))}
    </div>
  );
}
