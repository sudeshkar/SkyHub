"use client";

import { usePathname } from "next/navigation";
import { useId, useRef, useState, useTransition, type FormEvent, type KeyboardEvent } from "react";
import { askAssistant, type ChatTurn } from "@/app/assistant/actions";

const MAX_MESSAGE = 1000;
const MAX_TURNS = 6;

type Entry =
  | { role: "traveller"; text: string }
  | { role: "assistant"; text: string; sources: string[]; usedBooking: boolean };

/**
 * The traveller assistant on every traveller page (ADR-0095). The conversation lives only in this component's state:
 * nothing is stored, and a reload starts afresh. Replies are shown as plain text, never as HTML, so nothing a model
 * writes can run in the page. On the Manage page the server action receives the manage cookie, so the assistant can
 * answer from the booking open there; this component never sees the booking.
 */
export function AssistantChat() {
  const pathname = usePathname();
  const [open, setOpen] = useState(false);
  const [entries, setEntries] = useState<Entry[]>([]);
  const [draft, setDraft] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();
  const panelId = useId();
  const boxRef = useRef<HTMLTextAreaElement>(null);

  if (pathname?.startsWith("/ops")) return null; // staff have their own tools

  const canSend = draft.trim().length > 0 && !pending;

  function send() {
    const text = draft.trim();
    if (!text || pending) return;
    const history: ChatTurn[] = entries.slice(-MAX_TURNS).map((entry) => ({ role: entry.role, text: entry.text }));
    setError(null);
    setDraft("");
    setEntries((current) => [...current, { role: "traveller", text }]);
    startTransition(async () => {
      const result = await askAssistant(text, history);
      if (result.kind === "answer") {
        setEntries((current) => [
          ...current,
          { role: "assistant", text: result.reply, sources: result.sources, usedBooking: result.usedBooking },
        ]);
      } else {
        // The message did not get an answer: it leaves the conversation and goes back in the box to try again.
        setEntries((current) => current.slice(0, -1));
        setDraft(text);
        setError(result.message);
      }
    });
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    send();
  }

  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      send();
    }
  }

  return (
    <div className="fixed bottom-4 right-4 z-50 flex flex-col items-end gap-3">
      {open && (
        <section
          id={panelId}
          aria-label="SkyHub assistant"
          className="flex max-h-[70vh] w-[min(24rem,calc(100vw-2rem))] flex-col rounded-xl border border-line bg-white shadow-[0_10px_28px_-14px_rgb(10_80_143/0.35)]"
        >
          <header className="flex items-start justify-between gap-3 border-b border-line bg-wash/60 px-4 py-3">
            <div>
              <h2 className="font-display text-base text-ink">Ask SkyHub</h2>
              <p className="text-xs text-muted">
                Answers from SkyHub&apos;s help centre, flights and, on the Manage page, your open booking. It can&apos;t
                change a booking. SkyHub is a demonstration.
              </p>
            </div>
            <button
              type="button"
              onClick={() => setOpen(false)}
              aria-label="Close the assistant"
              className="rounded-full px-2 py-1 text-muted hover:bg-wash hover:text-ink"
            >
              ×
            </button>
          </header>

          <ol aria-live="polite" aria-busy={pending} className="flex-1 space-y-3 overflow-y-auto px-4 py-3 text-sm">
            {entries.length === 0 && (
              <li className="text-muted">Try &ldquo;When can I check in?&rdquo; or &ldquo;Is my flight delayed?&rdquo;</li>
            )}
            {entries.map((entry, index) =>
              entry.role === "traveller" ? (
                <li key={index} className="ml-8 rounded-lg bg-accent/10 px-3 py-2 text-text">
                  <span className="sr-only">You: </span>
                  {entry.text}
                </li>
              ) : (
                <li key={index} className="mr-8 rounded-lg border border-line px-3 py-2 text-text">
                  <span className="sr-only">SkyHub: </span>
                  <p className="whitespace-pre-line">{entry.text}</p>
                  {entry.usedBooking && <p className="mt-2 text-xs text-muted">From the booking you have open</p>}
                  {entry.sources.length > 0 && (
                    <p className="mt-2 text-xs text-muted">From the help centre: {entry.sources.join(", ")}</p>
                  )}
                </li>
              ),
            )}
            {pending && <li className="text-muted">Thinking…</li>}
          </ol>

          {error && (
            <p role="alert" className="mx-4 mb-2 rounded-lg bg-amber-bg px-3 py-2 text-xs text-amber">
              {error}
            </p>
          )}

          <form onSubmit={onSubmit} className="flex items-end gap-2 border-t border-line px-4 py-3">
            <label className="sr-only" htmlFor={`${panelId}-message`}>
              Your message
            </label>
            <textarea
              id={`${panelId}-message`}
              ref={boxRef}
              value={draft}
              onChange={(event) => setDraft(event.target.value)}
              onKeyDown={onKeyDown}
              maxLength={MAX_MESSAGE}
              rows={2}
              className="flex-1 resize-none rounded-lg border border-line px-3 py-2 text-sm focus:border-accent focus:outline-none"
            />
            <button
              type="submit"
              disabled={!canSend}
              className="rounded-full bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-strong disabled:cursor-not-allowed disabled:opacity-50"
            >
              Send
            </button>
          </form>
        </section>
      )}

      <button
        type="button"
        onClick={() => {
          setOpen((current) => !current);
          setTimeout(() => boxRef.current?.focus(), 0);
        }}
        aria-expanded={open}
        aria-controls={panelId}
        className="rounded-full bg-accent px-5 py-3 text-sm font-semibold text-white shadow-lg hover:bg-accent-strong"
      >
        Ask SkyHub
      </button>
    </div>
  );
}
