import Link from "next/link";
import { SearchForm } from "@/components/search-form";

const SAMPLE_ROUTES = [
  { origin: "CMB", destination: "JFK", label: "Colombo to New York", note: "Changes airport in Dubai" },
  { origin: "BOM", destination: "JFK", label: "Mumbai to New York", note: "Two ways through Dubai" },
  { origin: "CMB", destination: "LHR", label: "Colombo to London", note: "Same-airport connection" },
];

// Sample searches use a fixed date in the seeded timetable.
const SAMPLE_DATE = "2026-11-02";

export default function Home() {
  return (
    <>
      <section className="bg-ink pb-24 pt-14 text-ivory">
        <div className="mx-auto max-w-5xl px-4">
          <h1 className="max-w-2xl font-display text-4xl leading-tight sm:text-5xl">
            Two Dubai airports. One journey.
          </h1>
          <p className="mt-4 max-w-xl text-lg text-sand">
            Connect through Dubai International (DXB) or Al Maktoum (DWC). If your connection changes airport, you
            will see it before you book.
          </p>
        </div>
      </section>

      <section aria-labelledby="search-heading" className="mx-auto -mt-14 max-w-5xl px-4">
        <div className="rounded-lg bg-white p-6 shadow-[0_1px_3px_rgb(15_27_45/0.12)]">
          <h2 id="search-heading" className="mb-4 text-lg font-semibold">
            Find a flight
          </h2>
          <SearchForm />
        </div>
      </section>

      <section aria-labelledby="samples-heading" className="mx-auto max-w-5xl px-4 py-12">
        <h2 id="samples-heading" className="text-sm font-semibold text-muted">
          Try a sample route on {SAMPLE_DATE}
        </h2>
        <ul className="mt-3 grid gap-px overflow-hidden rounded-lg border border-sand bg-sand sm:grid-cols-3">
          {SAMPLE_ROUTES.map((route) => (
            <li key={route.label} className="bg-ivory">
              <Link
                href={`/search?origin=${route.origin}&destination=${route.destination}&date=${SAMPLE_DATE}`}
                className="block px-4 py-4 hover:bg-sand-soft"
              >
                <span className="block font-medium">{route.label}</span>
                <span className="block text-sm text-muted">{route.note}</span>
              </Link>
            </li>
          ))}
        </ul>
      </section>
    </>
  );
}
