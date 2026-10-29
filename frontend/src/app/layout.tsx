import type { Metadata } from "next";
import { Fraunces, IBM_Plex_Mono, IBM_Plex_Sans } from "next/font/google";
import Image from "next/image";
import Link from "next/link";
import logo from "@/assets/skyhub-logo.png";
import { SiteFooter } from "@/components/site-footer";
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
        <header className="border-b border-sand bg-white">
          <div className="mx-auto flex h-20 max-w-5xl items-center justify-between px-4">
            <Link href="/" aria-label="SkyHub home" className="shrink-0">
              <Image src={logo} alt="SkyHub" className="h-14 w-auto sm:h-16" />
            </Link>
            <nav aria-label="Main" className="flex gap-6 text-sm font-medium text-ink">
              <Link href="/" className="hover:text-copper">
                Book
              </Link>
              <Link href="/manage" className="hover:text-copper">
                Manage booking
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
