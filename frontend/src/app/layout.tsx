import type { Metadata } from "next";
import { Fraunces, IBM_Plex_Mono, IBM_Plex_Sans } from "next/font/google";
import Link from "next/link";
import "./globals.css";

const plexSans = IBM_Plex_Sans({
  variable: "--font-plex-sans",
  subsets: ["latin"],
  weight: ["400", "500", "600"],
});

const plexMono = IBM_Plex_Mono({
  variable: "--font-plex-mono",
  subsets: ["latin"],
  weight: ["400", "500"],
});

const fraunces = Fraunces({
  variable: "--font-fraunces",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "SkyHub — flights through Dubai",
  description: "Search connections through Dubai International (DXB) and Al Maktoum International (DWC).",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html
      lang="en"
      className={`${plexSans.variable} ${plexMono.variable} ${fraunces.variable} h-full antialiased`}
    >
      <body className="flex min-h-full flex-col">
        <header className="bg-ink text-ivory">
          <div className="mx-auto flex h-16 max-w-5xl items-center justify-between px-4">
            <Link href="/" className="font-display text-2xl tracking-tight">
              SkyHub
            </Link>
            <nav aria-label="Main" className="flex gap-6 text-sm text-sand">
              <Link href="/" className="hover:text-ivory">
                Book
              </Link>
              <Link href="/manage" className="hover:text-ivory">
                Manage booking
              </Link>
            </nav>
          </div>
        </header>
        <main className="flex-1">{children}</main>
        <footer className="border-t border-sand">
          <p className="mx-auto max-w-5xl px-4 py-6 text-sm text-muted">
            SkyHub is a portfolio project, not a real airline. Times are shown in each airport&apos;s local time.
          </p>
        </footer>
      </body>
    </html>
  );
}
