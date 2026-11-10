# Clear Sky: colours, type and buttons

The website's look, adapted from the cool, saturated blues and glossy buttons of a modern app landing page (reference:
supaste.com). It replaces the earlier warm "Desert Dusk" palette. Adapted, not copied: the
palette is tuned for readable text, and the layout of each page is SkyHub's own.

## Colours

Every colour is a token in `frontend/src/app/globals.css`. Components use the token names only, never raw hex values.
Names say what a colour is for, so a future palette changes values there and nowhere else.

| Token | Value | Used for |
|---|---|---|
| `ink` | #0b1b33 | Headings, dark surfaces |
| `ink-soft` | #23395a | Seats chosen for another passenger, the plane outline |
| `canvas` | #f4f8fd | Page background (with a soft blue glow at the top) |
| `line` | #d9e4f2 | Borders and dividers |
| `wash` | #ecf3fb | Quiet fills and hover |
| `accent` | #0a72e6 | Links, highlights, buttons. 4.8:1 on white, so it passes for text |
| `accent-strong` | #0a508f | Hover and pressed; the top of the footer gradient |
| `sky` | #8ad8fc | The light end of gradients and the page glow |
| `text` / `muted` | #14243a / #566579 | Body text / secondary text (5.6:1 on the canvas) |
| `amber`, `danger` | | Warnings and errors, unchanged in role |

The source's brightest blue (#087cf8) is about 4.2:1 on white, just under the 4.5:1 needed for small text, so `accent`
is a shade deeper. The bright blue still appears at the top of the button gradient.

## Type

- **Inter** for text and headings. Display headings (`font-display`) are bold with tight letter-spacing (-0.025em).
- **IBM Plex Mono** stays for booking references, flight numbers, seats and times.

## Buttons and surfaces

- **Main action** (`btn-primary`): a pill with a blue gradient, a light top edge and a soft blue glow; it brightens on
  hover and presses down one pixel. One per screen where possible.
- **Other buttons**: white pills with a `line` border, turning `accent` on hover. Cancel is a red-outlined pill.
- **Glass** (`glass`): frosted white over what scrolls beneath, used for the sticky site header and the sticky heading
  row of the booking steps.
- **Footer**: a gradient from `accent-strong` down to `ink`.
- Seats, inputs and cards keep their small rounded corners: pills are for things you press.
