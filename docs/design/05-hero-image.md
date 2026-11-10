# Hero image specification

The home page hero is a photo that fills the full width of the screen. It is scaled to **cover** a short banner box,
so part of the photo is trimmed, differently on each device. Design the image for the trimming, and nothing
important is ever cut off.

## Make this

| | |
|---|---|
| **Canvas** | **2400 × 900 px** (8:3). The current photo is 2048 × 768, which also works |
| **Look** | The "Clear Sky" palette (docs/design/06-clear-sky.md): a saturated blue sky, white aircraft, glass terminal, cool light, no warm or sunset tones |
| **Text** | None. The real logo is in the header; text inside a photo blurs when scaled and cannot be read by screen readers or translated |
| **Format** | JPEG or WebP, sRGB, quality about 80 |
| **Weight** | Under 300 KB after export (the site also re-optimizes it) |
| **File** | `frontend/src/assets/hero-airport.jpg` (replace it) |

## The box the photo fills

The box is wider than the photo on every screen, so the photo always spans the full width and only the **top and
bottom** are trimmed. It is anchored 40% down (`object-[50%_40%]` in `frontend/src/app/page.tsx`), so a little more
sky goes from the top than tarmac from the bottom.

| Screen | Box | Example size | Rows of the photo that show |
|---|---|---|---|
| Phone, under 640 px | 3:1 | 390 × 130 | y 4–93% |
| Tablet and desktop, 640–2014 px | 1672:425, about 3.93:1 | 1440 × 366 | y 13–81% |
| Wide screens, over 2014 px | capped at 512 px high | 2560 × 512 | y 19–72% |

The hero is kept short so the search card, which is what people come for, sits near the top of the screen.

## Where to put things

- **Keep every important thing inside the band y 19–72%,** across the full width. That is visible on every device up to 2560 px wide.
- In the current photo the traveller's face sits at about y 31–49% and the aircraft's nose at about y 47–75%.
- Let sky run off the top and tarmac off the bottom: only those are ever trimmed.
- The bottom of the visible band should be calm (tarmac): on desktop the search card overlaps it by about 48 px.
