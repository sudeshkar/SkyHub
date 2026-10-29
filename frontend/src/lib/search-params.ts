import type { SearchQuery } from "./api/search";

type RawParams = Record<string, string | string[] | undefined>;

export type ParsedSearch = { ok: true; query: SearchQuery } | { ok: false; message: string };

const AIRPORT_CODE = /^[A-Z]{3}$/;
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

function single(value: string | string[] | undefined): string {
  return (Array.isArray(value) ? value[0] : value)?.trim().toUpperCase() ?? "";
}

/** Validates the URL before calling the API, so users get friendly messages instead of server errors. */
export function parseSearch(params: RawParams): ParsedSearch {
  const origin = single(params.origin);
  const destination = single(params.destination);
  const date = single(params.date);

  if (!AIRPORT_CODE.test(origin) || !AIRPORT_CODE.test(destination)) {
    return { ok: false, message: "Choose both a departure and an arrival airport." };
  }
  if (origin === destination) {
    return { ok: false, message: "Departure and arrival airports must be different." };
  }
  if (!ISO_DATE.test(date) || Number.isNaN(Date.parse(date))) {
    return { ok: false, message: "Choose a valid departure date." };
  }
  return { ok: true, query: { origin, destination, date } };
}
