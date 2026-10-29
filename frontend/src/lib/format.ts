export function formatDuration(minutes: number): string {
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  if (hours === 0) return `${rest}m`;
  return rest === 0 ? `${hours}h` : `${hours}h ${rest}m`;
}

/** "07:30" in the airport's own time zone. */
export function localTime(isoInstant: string, timeZone: string): string {
  return new Intl.DateTimeFormat("en-GB", {
    timeZone,
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).format(new Date(isoInstant));
}

/** Calendar date (YYYY-MM-DD) of an instant in a given time zone. */
export function localDate(isoInstant: string, timeZone: string): string {
  // en-CA formats dates as YYYY-MM-DD.
  return new Intl.DateTimeFormat("en-CA", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(new Date(isoInstant));
}

/** Whole days between two local calendar dates, e.g. 1 for an arrival "the next day". */
export function dayOffset(
  from: { instant: string; timeZone: string },
  to: { instant: string; timeZone: string },
): number {
  const start = Date.parse(localDate(from.instant, from.timeZone));
  const end = Date.parse(localDate(to.instant, to.timeZone));
  return Math.round((end - start) / 86_400_000);
}

/** "Mon 2 Nov 2026" for a date-only string such as "2026-11-02". */
export function formatSearchDate(isoDate: string): string {
  return new Intl.DateTimeFormat("en-GB", {
    timeZone: "UTC",
    weekday: "short",
    day: "numeric",
    month: "short",
    year: "numeric",
  }).format(new Date(`${isoDate}T00:00:00Z`));
}

export function formatMoney(amount: number, currency: string): string {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency,
    maximumFractionDigits: 0,
  }).format(amount);
}

/** "2 Nov, 14:30 UTC": an instant in UTC with the zone spelled out. */
export function utcDateTime(isoInstant: string): string {
  const text = new Intl.DateTimeFormat("en-GB", {
    timeZone: "UTC",
    day: "numeric",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).format(new Date(isoInstant));
  return `${text} UTC`;
}
