import Image from "next/image";
import Link from "next/link";
import { SearchForm } from "@/components/search-form";
import heroImage from "@/assets/hero-airport.jpg";

const SAMPLE_ROUTES = [
  { origin: "CMB", destination: "JFK", label: "Colombo to New York", note: "Changes airport in Dubai" },
  { origin: "BOM", destination: "JFK", label: "Mumbai to New York", note: "Two ways through Dubai" },
  { origin: "CMB", destination: "LHR", label: "Colombo to London", note: "Same-airport connection" },
];

/** The brand line over the hero, two lines of words. */
const TAGLINE = [
  ["Your", "journey,"],
  ["our", "priority"],
];

/** One word of the tagline, rising in a little after the one before it. */
function RiseWord({ word, order }: { word: string; order: number }) {
  return (
    <>
      <span className="rise-word" style={{ animationDelay: `${150 + order * 120}ms` }}>
        {word}
      </span>{" "}
    </>
  );
}

// Sample searches use a fixed date in the seeded timetable.
const SAMPLE_DATE = "2026-11-02";

export default function Home() {
  return (
    <>
      {/*
        Hero proportions (design the photo for these): a short banner, so the search card sits near the top of the
        screen. The photo is 8:3 (2048 x 768); the box is about 3.9:1 from 640px up and 3:1 on phones, so the photo is
        trimmed top and bottom, anchored 40% down: the sky above the traveller's head and the tarmac under the search
        card go, the traveller, the aircraft and the terminal stay. Never taller than 32rem. See docs/design/05-hero-image.md.
      */}
      <section className="bg-accent-strong">
        <div className="relative aspect-[3/1] max-h-[32rem] w-full sm:aspect-[1672/425]">
          <Image
            src={heroImage}
            alt="A smiling traveller holding a passport and boarding pass beside a white aircraft at a glass terminal under a clear blue sky."
            fill
            preload
            placeholder="blur"
            sizes="100vw"
            className="object-cover object-[50%_40%]"
          />
          {/*
            The brand line over the open sky on the left, away from the traveller and the search card. A soft dark
            gradient behind it keeps white text readable on bright cloud. The words rise in one after another, once, in
            CSS (globals.css), so they show as soon as the page does; with reduced motion they are simply there. It is
            decorative: the page's heading is in the search card below. Phones skip it, as the banner there is very short.
          */}
          <div className="absolute inset-0 hidden items-center bg-linear-to-r from-ink/55 via-ink/15 to-transparent pb-24 sm:flex lg:pb-28">
            <div className="mx-auto w-full max-w-5xl px-4">
              <p className="font-display text-3xl uppercase leading-[1.05] tracking-tight text-white [text-shadow:0_2px_18px_rgb(11_27_51/0.45)] lg:text-5xl">
                <span className="block">
                  {TAGLINE[0].map((word, i) => (
                    <RiseWord key={word} word={word} order={i} />
                  ))}
                </span>
                <span className="block text-sky">
                  {TAGLINE[1].map((word, i) => (
                    <RiseWord key={word} word={word} order={TAGLINE[0].length + i} />
                  ))}
                </span>
              </p>
            </div>
          </div>
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
        <ul className="mt-3 grid gap-px overflow-hidden rounded-lg border border-line bg-line sm:grid-cols-3">
          {SAMPLE_ROUTES.map((route) => (
            <li key={route.label} className="bg-canvas">
              <Link
                href={`/search?origin=${route.origin}&destination=${route.destination}&date=${SAMPLE_DATE}`}
                className="block px-4 py-4 hover:bg-wash"
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
