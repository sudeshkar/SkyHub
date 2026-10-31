# ADR-0050: HTML email beside the plain text

Status: Accepted

## Context

Every email SkyHub sends is plain text (`EmailComposer`). That is the safest form and it stays: it is what a screen reader,
a watch and a strict mail client show. But most travellers see it as unstyled, with links that are not always tappable.
The roadmap listed "HTML email" as an open limit.

## Decision

Each email is sent as a multipart message: the **plain text exactly as before**, plus an **HTML version derived from it**.

- `HtmlBody.from(text)` (application layer, pure) turns the text into a small, self-contained HTML document: blank-line
  separated paragraphs become `<p>`, single line breaks become `<br>`, and web addresses (`http`/`https` only) become links.
- **Everything is escaped first.** Names, flight numbers and any other text reach the HTML as text, never as markup.
  Links are found in the already-escaped text, and the full stop or comma that ends a sentence is not part of the link.
- **No remote content:** no images, no scripts, no tracking pixels, no external styles. This keeps the earlier promise of
  no tracking (ADR-0019) and means opening the email tells us nothing.
- The composers do not change. Because the HTML is derived from the text in one place, a new email type gets both forms
  with no extra code (open for extension, closed for modification), and the two cannot drift apart.
- The rendering happens in the SMTP adapter, not in `EmailMessage`: the log adapter and the rules do not know HTML exists.

## Consequences

- Plain-text-only clients are unchanged.
- The HTML is deliberately plain (readable type, one column). Branding, a logo and a boarding-pass attachment are later
  slices; the attachment needs the pass bytes carried on the event and is not part of this one.
- Links in the HTML are the same links as in the text; both are only as private as the mailbox.
