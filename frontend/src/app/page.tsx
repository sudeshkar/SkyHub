import Image from "next/image";
import Link from "next/link";
import { ScrollJourney } from "@/components/scroll-journey";
import { SearchForm } from "@/components/search-form";
import heroImage from "@/assets/hero-sunset-airport.jpg";

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
      {/*
        Hero proportions (design the photo for these): the photo's own 8:3 shape from 640px up, so nothing is
        cropped on tablets and desktops; 16:9 on phones, which trims the sides; never taller than 48rem, which
        trims the top and bottom on very wide screens. See docs/design/05-hero-image.md.
      */}
      <section className="bg-ink">
        <div className="relative aspect-video max-h-[48rem] w-full sm:aspect-[8/3]">
          <Image
            src={heroImage}
            alt="A smiling traveller holding a passport beside an aircraft at the gate as the sun sets over the airport. SkyHub: your journey, our priority."
            fill
            preload
            placeholder="blur"
            sizes="100vw"
            className="object-cover object-[70%_20%] sm:object-[50%_20%]"
          />
        </div>
      </section>

      {/* relative + z-10: the photo above is positioned, so without this it paints over the card's top edge. */}
      <section aria-labelledby="search-heading" className="relative z-10 mx-auto -mt-12 max-w-5xl px-4">
        <div className="rounded-lg bg-white p-6 shadow-[0_2px_12px_rgb(15_27_45/0.18)]">
          <h1 id="search-heading" className="font-display text-2xl leading-tight sm:text-3xl">
            Two Dubai airports. One journey.
          </h1>
          <p className="mb-5 mt-2 max-w-2xl text-muted">
            Connect through Dubai International (DXB) or Al Maktoum (DWC). If your connection changes airport, you
            will see it before you book.
          </p>
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

      <ScrollJourney />
    </>
  );
}
