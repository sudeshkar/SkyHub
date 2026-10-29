# Hero image specification

The home page hero is a photo that fills the full width of the screen. It is scaled to **cover** a box whose
proportions change with the screen, so the edges are trimmed differently on each device. Design the image for
the trimming, and nothing important is ever cut off.

## Make this

| | |
|---|---|
| **Canvas** | **2400 × 900 px** (8:3). The current photo is 2000 × 750, which also works |
| **Format** | JPEG or WebP, sRGB, quality about 80 |
| **Weight** | Under 300 KB after export (the site also re-optimizes it) |
| **File** | `frontend/src/assets/hero-sunset-airport.jpg` (replace it) |

2400 px is wide enough for a 1200 px layout at 2× (retina). Larger files only slow the page down.

## The box the photo fills

| Screen | Box | Example size | What is trimmed from an 8:3 photo |
|---|---|---|---|
| Phone, under 640 px | 16:9 | 390 × 219 | Left and right: the middle 67% of the width shows, shifted right so the person stays in view |
| Tablet and desktop, 640–1920 px | **8:3, the photo's own shape** | 1440 × 540 | **Nothing** |
| Wide screens, over 1920 px | capped at 768 px high | 2560 × 768 | Top and bottom: about 20% in total |

## Where to put things

- **Keep every important thing inside the middle 70% of the width (x 15–85%) and the band y 8–85%.** That is visible on every device.
- On phones the visible strip is x 23–90%. The person at the right edge and the logo in the centre both fit, so keep them within it.
- Let sky, clouds and runway run to all four edges: only those are ever trimmed.
- The bottom 12% should be calm (tarmac or soft sky): on desktop the search card overlaps the bottom edge by about 48 px.

## About text inside the photo

The photo currently carries its own SkyHub logo and tagline. That is fine for the hero, but note that:

- the lettering is not the same design as the official logo (the header uses the official one), so two slightly different logos appear;
- text inside a photo is blurry when enlarged, cannot be translated (Arabic is planned) and cannot be read by screen readers (the page gives the photo a descriptive alt text instead).

A photo without text, with the real logo laid over it by the page, would be sharper and consistent.
