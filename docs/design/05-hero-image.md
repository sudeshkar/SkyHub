# Hero image specification

The home page hero is a photo that fills the full width of the screen. It is scaled to **cover** a box whose
proportions change with the screen, so the edges are trimmed differently on each device. Design the image for
the trimming, and nothing important is ever cut off.

## Make this

| | |
|---|---|
| **Canvas** | **2880 × 960 px** (3:1) |
| **Format** | JPEG or WebP, sRGB, quality about 80 |
| **Weight** | Under 300 KB after export (the site also re-optimizes it) |
| **File** | `frontend/src/assets/hero-sunset-airport.jpg` (replace it) |
| **Text and logos** | None baked in. The real logo and the headline are added by the page |

2880 px is wide enough for a 1440 px screen at 2× (retina). Larger files only slow the page down.

## The box the photo fills

| Screen | Box (ratio) | Example size | What is trimmed from a 3:1 photo |
|---|---|---|---|
| Phone, under 640 px | 4:3 | 390 × 292 | Left and right: only the middle 44% of the width shows |
| Tablet, 640–1023 px | 2:1 | 768 × 384 | Left and right: the middle 67% shows |
| Laptop and desktop, 1024–1920 px | 3:1 | 1440 × 480 | Nothing |
| Wide screens, over 1920 px | 4:1 or wider, capped at 640 px high | 2560 × 640 | Top and bottom: about 12% each |

## Where to put things

```
 x: 0%                 22%                         78%                100%
    ┌──────────────────┬───────────────────────────┬──────────────────┐ y: 0%
    │   scenery only   │                           │   scenery only   │
    │   (sky, runway)  │      SAFE AREA            │   (sky, runway)  │ y: 12%
    │                  │   keep every important    │                  │
    │  can be cropped  │   thing inside here:      │  can be cropped  │
    │  on phones and   │   aircraft, people,       │  on phones and   │
    │  tablets         │   the sun                 │  tablets         │ y: 88%
    │                  │                           │                  │
    └──────────────────┴───────────────────────────┴──────────────────┘ y: 100%
```

- **Safe area: the middle 56% of the width (x 22–78%) and the middle 76% of the height (y 12–88%).** The plane, the person and the focal point go here. They stay visible on every device.
- Let the sky, clouds, runway and horizon **run to all four edges**. That is what is cropped.
- Keep the **bottom 15% calm** (tarmac or soft sky). On desktop the search card overlaps the bottom edge by about 48 px.
- Keep the **top 10% simple**. Nothing sits over it, but wide screens trim it.
- Put the subject **slightly right of centre** only if the left is open space: the page places no text over the photo.

## Optional: a separate phone image

The 4:3 phone box shows only a narrow slice of a 3:1 photo. For the best phone result, also make
**1200 × 900 px (4:3)**, same style, subject centred, and send it along. Without it, phones show the middle of the wide image.

## Why no baked-in logo or text

- The generated logo in the first image did not match the real SkyHub logo (different letter styling), so two logos appeared on the page.
- Text inside a photo is blurry when scaled, cannot be translated (Arabic is planned) and cannot be read by screen readers.
- The page already shows the real logo in the header. If a logo or tagline over the photo is wanted, it is added as a sharp overlay in a reversed (white) logo version.
