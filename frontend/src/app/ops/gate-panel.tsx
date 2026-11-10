"use client";

import { useActionState, useState } from "react";
import { gateScan, type GateState } from "./actions";
import { CameraScanner } from "./camera-scanner";
import { REFUSALS } from "./gate-reasons";

const field = "mt-1 block w-full rounded-md border border-line bg-white px-3 py-2.5 font-mono";


/**
 * The gate (ADR-0062): scan or paste a boarding pass, check it, and board the passenger. A scanner types the pass like a keyboard, so
 * the field is all it needs, and the phone's camera can fill it by reading the pass's QR code (ADR-0072). A read only fills the field:
 * checking never boards anyone, and boarding is its own button a person presses. The pass is not shown back and nothing is kept.
 */
export function GatePanel() {
  const [state, action, pending] = useActionState<GateState, FormData>(gateScan, { status: "idle" });
  const [barcode, setBarcode] = useState("");
  const [scanning, setScanning] = useState(false);

  /** Sends the pass and empties the field, so the next passenger starts clean (the camera can fill it again). */
  const send = (form: FormData) => {
    action(form);
    setBarcode("");
  };

  return (
    <section aria-label="Gate" className="space-y-6">
      <h2 className="font-display text-xl">Gate</h2>
      <p className="max-w-md text-sm text-muted">
        Scan or paste a boarding pass. Checking a pass does not board the passenger; press Board passenger to board them. A passenger boards
        once.
      </p>

      {state.status === "good" && (
        <div role="status" className="max-w-md border-l-4 border-accent bg-white px-4 py-3">
          <p className="font-semibold">
            {state.intent === "board" ? `${state.pass.passengerName} has boarded.` : `Good pass for ${state.pass.passengerName}.`}
          </p>
          <p className="mt-1 text-sm">
            Flight {state.pass.flightNumber} on {state.pass.departureDate}, seat {state.pass.seatNumber}, boarding number {state.pass.sequence}.
          </p>
          {state.intent === "check" && <p className="mt-1 text-sm text-muted">Not boarded yet. Press Board passenger to board them.</p>}
        </div>
      )}
      {state.status === "refused" && (
        <p role="alert" className="max-w-md border-l-4 border-danger bg-white px-4 py-3 font-semibold text-danger">
          {REFUSALS[state.reason]}
        </p>
      )}
      {state.status === "error" && (
        <p role="alert" className="max-w-md border-l-4 border-danger bg-white px-4 py-3 text-sm text-danger">
          {state.message}
        </p>
      )}
      {state.status === "signed-out" && (
        <p role="alert" className="max-w-md border-l-4 border-amber-line bg-amber-bg px-4 py-3 text-sm text-text">
          Your session has ended, or your role cannot do this. <a href="/ops" className="underline">Sign in again</a>.
        </p>
      )}

      {scanning && (
        <CameraScanner
          onCode={(text) => {
            setBarcode(text);
            setScanning(false);
          }}
          onClose={() => setScanning(false)}
        />
      )}

      <form action={send} className="tool-form">
        <label className="block text-sm font-medium">
          Scanned boarding pass
          <input name="barcode" required maxLength={300} autoComplete="off" autoFocus spellCheck={false} className={field}
            value={barcode} onChange={(event) => setBarcode(event.target.value)} />
        </label>
        {!scanning && (
          <button type="button" onClick={() => setScanning(true)} className="rounded-full border border-line px-4 py-2 text-sm hover:bg-wash">
            Scan with camera
          </button>
        )}
        <div className="flex flex-wrap gap-3">
          <button
            type="submit"
            name="intent"
            value="check"
            disabled={pending}
            className="rounded-md border border-accent px-6 py-3 font-semibold text-accent hover:bg-wash disabled:opacity-60"
          >
            Check pass
          </button>
          <button
            type="submit"
            name="intent"
            value="board"
            disabled={pending}
            className="btn-primary px-6 py-3 font-semibold text-white disabled:opacity-60"
          >
            Board passenger
          </button>
        </div>
      </form>
    </section>
  );
}
