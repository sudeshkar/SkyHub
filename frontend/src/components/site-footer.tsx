import Image from "next/image";
import Link from "next/link";
import logo from "@/assets/skyhub-logo.png";

const HUBS = [
  { code: "DXB", name: "Dubai International" },
  { code: "DWC", name: "Al Maktoum International" },
] as const;

const linkClass = "text-line hover:text-white";

export function SiteFooter() {
  return (
    <footer className="bg-linear-to-b from-accent-strong to-ink text-canvas">
      <div className="mx-auto grid max-w-5xl gap-10 px-4 py-12 sm:grid-cols-2 lg:grid-cols-[1.4fr_1fr_1fr_1fr]">
        <div>
          <Link href="/" aria-label="SkyHub home" className="inline-block">
            {/* The logo's navy lettering would vanish on this background, so it is shown in white. */}
            <Image src={logo} alt="SkyHub" className="h-16 w-auto brightness-0 invert" />
          </Link>
          <p className="mt-4 max-w-xs text-sm text-line">Search, connect and book flights through Dubai&apos;s two airports.</p>
        </div>

        <nav aria-label="Footer">
          <h2 className="text-sm font-semibold tracking-wide text-canvas">Travel</h2>
          <ul className="mt-4 space-y-3 text-sm">
            <li>
              <Link href="/" className={linkClass}>
                Book a flight
              </Link>
            </li>
            <li>
              <Link href="/manage" className={linkClass}>
                Manage booking
              </Link>
            </li>
          </ul>
        </nav>

        <div>
          <h2 className="text-sm font-semibold tracking-wide text-canvas">Our Dubai hubs</h2>
          <ul className="mt-4 space-y-3 text-sm">
            {HUBS.map((hub) => (
              <li key={hub.code}>
                <span className="font-mono text-canvas">{hub.code}</span>
                <span className="block text-line">{hub.name}</span>
              </li>
            ))}
          </ul>
        </div>

        <div>
          <h2 className="text-sm font-semibold tracking-wide text-canvas">Project</h2>
          <ul className="mt-4 space-y-3 text-sm">
            <li>
              <a
                href="https://github.com/sudeshkar/SkyHub"
                target="_blank"
                rel="noopener noreferrer"
                className={linkClass}
              >
                Source code on GitHub<span className="sr-only"> (opens in a new tab)</span>
              </a>
            </li>
          </ul>
        </div>
      </div>

      <div className="border-t border-canvas/15">
        <p className="mx-auto max-w-5xl px-4 py-5 text-sm text-line">&copy; SkyHub</p>
      </div>
    </footer>
  );
}
