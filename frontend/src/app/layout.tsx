import type { Metadata } from "next";
import { IBM_Plex_Mono, Inter } from "next/font/google";
import Image from "next/image";
import Link from "next/link";
import logo from "@/assets/skyhub-logo.png";
import { SiteFooter } from "@/components/site-footer";
import "./globals.css";

// Inter for text and headings alike; headings are set bold and tight (globals.css). Plex Mono stays for codes and times.
const inter = Inter({
  variable: "--font-inter",
  subsets: ["latin"],
});

const plexMono = IBM_Plex_Mono({
  variable: "--font-plex-mono",
  subsets: ["latin"],
  weight: ["400", "500"],
});

export const metadata: Metadata = {
  title: "SkyHub — flights through Dubai",
  description: "Search connections through Dubai International (DXB) and Al Maktoum International (DWC).",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html
      lang="en"
      className={`${inter.variable} ${plexMono.variable} h-full antialiased`}
    >
      <body className="flex min-h-full flex-col">
        {/* Frosted glass that stays at the top while the page scrolls under it. */}
        <header className="glass sticky top-0 z-40 border-b border-line/70">
          <div className="mx-auto flex h-20 max-w-5xl items-center justify-between px-4">
            <Link href="/" aria-label="SkyHub home" className="shrink-0">
              <Image src={logo} alt="SkyHub" className="h-14 w-auto sm:h-16" />
            </Link>
            <nav aria-label="Main" className="flex gap-1 text-sm font-medium text-ink sm:gap-2">
              <Link href="/" className="rounded-full px-3 py-2 hover:bg-wash hover:text-accent">
                Book
              </Link>
              <Link href="/manage" className="rounded-full px-3 py-2 hover:bg-wash hover:text-accent">
                Manage booking
              </Link>
              <Link href="/account" className="rounded-full px-3 py-2 hover:bg-wash hover:text-accent">
                Account
              </Link>
            </nav>
          </div>
        </header>
        <main className="flex-1">{children}</main>
        <SiteFooter />
      </body>
    </html>
  );
}
