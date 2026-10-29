import Form from "next/form";
import { AIRPORTS } from "@/lib/airports";

const fieldClass =
  "mt-1 block w-full rounded-md border border-sand bg-white px-3 py-2.5 text-base text-text focus:border-copper";

function AirportSelect({ name, label }: { name: string; label: string }) {
  return (
    <label className="block text-sm font-medium">
      {label}
      <select name={name} required defaultValue="" className={fieldClass}>
        <option value="" disabled>
          Choose an airport
        </option>
        {AIRPORTS.map((airport) => (
          <option key={airport.code} value={airport.code}>
            {airport.city} ({airport.code}) — {airport.name}
          </option>
        ))}
      </select>
    </label>
  );
}

export function SearchForm() {
  return (
    <Form action="/search" className="grid gap-4 sm:grid-cols-[1fr_1fr_auto_auto] sm:items-end">
      <AirportSelect name="origin" label="From" />
      <AirportSelect name="destination" label="To" />
      <label className="block text-sm font-medium">
        Departure date
        <input type="date" name="date" required className={fieldClass} />
      </label>
      <button
        type="submit"
        className="h-[46px] rounded-md bg-copper px-6 font-semibold text-white hover:bg-copper-strong"
      >
        Search flights
      </button>
    </Form>
  );
}
