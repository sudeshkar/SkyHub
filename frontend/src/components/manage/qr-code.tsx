import { qrCode } from "@/lib/qr";

/**
 * A QR code drawn inline as a picture (ADR-0071). The pass text is encoded here, in the page, and never put in an address: an image
 * fetched from a URL carrying the pass would write a signed boarding pass into server logs and caches. Always black on white, with the
 * quiet zone inside the picture, because a scanner cannot read a code drawn in a dark theme's colours. The text is not in the markup;
 * {@code label} is what a screen reader says.
 */
export function QrCode({ text, label, className }: { text: string; label: string; className?: string }) {
  const { size, path } = qrCode(text);
  return (
    <svg role="img" aria-label={label} viewBox={`0 0 ${size} ${size}`} shapeRendering="crispEdges" className={className}>
      <rect width={size} height={size} fill="#fff" />
      <path d={path} fill="#000" />
    </svg>
  );
}
