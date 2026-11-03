import type { ReadableSpan, SpanExporter } from "@opentelemetry/sdk-trace-base";

/** Attributes that hold an address. Some pages carry a one-time token in the query (/account/set-password?token=...), so none may keep it. */
const ADDRESS_KEYS = new Set(["http.url", "url.full", "url.original", "http.target", "http.request.target"]);

/** Parameters whose value is a secret wherever they turn up (an error message, a span name, an address in another attribute). */
const SECRET_PARAMETER = /([?&](?:token|code|key|secret|password|sig|signature|authorization)=)[^&#\s"']*/gi;

/** An address without its query or fragment: the path says what was asked for, the query can hold a token (ADR-0079). */
export function redactUrl(value: string): string {
  const cut = value.search(/[?#]/);
  if (cut < 0) return value;
  return value.slice(0, cut) + (value[cut] === "?" ? "?[redacted]" : "");
}

/** Hides the value of any parameter that holds a secret, in text that is not itself an address. */
export function scrubText(value: string): string {
  return value.replace(SECRET_PARAMETER, "$1[redacted]");
}

function redactAttributes(attributes: Record<string, unknown> | undefined): void {
  if (!attributes) return;
  for (const key of Object.keys(attributes)) {
    const value = attributes[key];
    if (typeof value !== "string") continue;   // numbers, flags and lists hold no address or token
    if (key === "url.query") attributes[key] = value.length > 0 ? "[redacted]" : value;
    else if (ADDRESS_KEYS.has(key)) attributes[key] = scrubText(redactUrl(value));
    else attributes[key] = scrubText(value);
  }
}

/**
 * Wraps the real exporter and cleans every span just before it leaves the process (ADR-0079): the query is cut from each address, and a
 * secret parameter is hidden in any other text, including the attributes of events such as an error. Done at export, after a span has
 * ended, so nothing the site itself reads is changed. It changes nothing that is not text.
 */
export class RedactingSpanExporter implements SpanExporter {
  constructor(private readonly inner: SpanExporter) {}

  export(spans: ReadableSpan[], resultCallback: Parameters<SpanExporter["export"]>[1]): void {
    for (const span of spans) {
      redactAttributes(span.attributes as Record<string, unknown> | undefined);
      for (const event of span.events ?? []) {
        redactAttributes(event.attributes as Record<string, unknown> | undefined);
      }
    }
    this.inner.export(spans, resultCallback);
  }

  shutdown(): Promise<void> {
    return this.inner.shutdown();
  }

  forceFlush(): Promise<void> {
    return this.inner.forceFlush?.() ?? Promise.resolve();
  }
}
