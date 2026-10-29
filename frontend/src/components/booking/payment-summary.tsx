import type { QuoteOutcome } from "@/lib/api/booking";
import { formatPrice } from "@/lib/format";

/**
 * Demo payment methods. SkyHub never sees card numbers: a real provider would issue these tokens in the browser.
 * The mock provider approves the first and declines or fails on the others, so every outcome can be tried.
 */
export const PAYMENT_METHODS = [
  { token: "tok_visa", label: "Visa ending 4242", note: "approved" },
  { token: "tok_declined", label: "Visa ending 0002", note: "will be declined" },
  { token: "tok_provider_down", label: "Visa ending 9995", note: "payment provider is down" },
] as const;

export const DEFAULT_PAYMENT_TOKEN: string = PAYMENT_METHODS[0].token;

export function PaymentSummary({
  quote,
  method,
  onMethodChange,
  disabled = false,
}: {
  /** null while the price is being worked out. */
  quote: QuoteOutcome | null;
  method: string;
  onMethodChange: (token: string) => void;
  disabled?: boolean;
}) {
  return (
    <section aria-labelledby="payment-heading" className="rounded-lg border border-sand bg-white p-5">
      <h3 id="payment-heading" className="font-semibold">
        Payment
      </h3>

      <p className="mt-3 flex items-baseline justify-between">
        <span className="text-muted">Total</span>
        {quote === null ? (
          <span aria-busy="true" className="text-sm text-muted">
            Working out the price…
          </span>
        ) : quote.kind === "quoted" ? (
          <span className="font-mono text-2xl">{formatPrice(quote.amount, quote.currency)}</span>
        ) : null}
      </p>
      {quote?.kind === "unavailable" && (
        <p role="alert" className="mt-2 text-sm text-danger">
          We couldn&apos;t work out the price right now. Please go back and try again.
        </p>
      )}

      <fieldset className="mt-4" disabled={disabled}>
        <legend className="text-sm font-medium">Pay with</legend>
        <div className="mt-2 space-y-2">
          {PAYMENT_METHODS.map((option) => (
            <label key={option.token} className="flex items-center gap-3 text-sm">
              <input
                type="radio"
                name="payment-method"
                value={option.token}
                checked={method === option.token}
                onChange={() => onMethodChange(option.token)}
              />
              <span>
                {option.label} <span className="text-muted">({option.note})</span>
              </span>
            </label>
          ))}
        </div>
      </fieldset>
      <p className="mt-3 text-xs text-muted">Demo payment: no real card is used and nothing is charged.</p>
    </section>
  );
}
