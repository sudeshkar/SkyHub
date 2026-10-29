// Reference data for display. Follow-up: serve this from the API so the UI and backend can't drift.
export type Airport = {
  code: string;
  city: string;
  name: string;
  timeZone: string;
};

export const AIRPORTS: readonly Airport[] = [
  { code: "CMB", city: "Colombo", name: "Bandaranaike International", timeZone: "Asia/Colombo" },
  { code: "BOM", city: "Mumbai", name: "Chhatrapati Shivaji Maharaj International", timeZone: "Asia/Kolkata" },
  { code: "DXB", city: "Dubai", name: "Dubai International", timeZone: "Asia/Dubai" },
  { code: "DWC", city: "Dubai", name: "Al Maktoum International", timeZone: "Asia/Dubai" },
  { code: "LHR", city: "London", name: "Heathrow", timeZone: "Europe/London" },
  { code: "JFK", city: "New York", name: "John F. Kennedy International", timeZone: "America/New_York" },
  { code: "SIN", city: "Singapore", name: "Changi", timeZone: "Asia/Singapore" },
  { code: "SYD", city: "Sydney", name: "Kingsford Smith", timeZone: "Australia/Sydney" },
];

const BY_CODE = new Map(AIRPORTS.map((airport) => [airport.code, airport]));

export function airport(code: string): Airport {
  return BY_CODE.get(code) ?? { code, city: code, name: code, timeZone: "UTC" };
}
