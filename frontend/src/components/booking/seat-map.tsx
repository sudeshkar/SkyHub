"use client";

import { Fragment, useCallback, useEffect, useRef, useState } from "react";
import type { Seat } from "@/lib/api/booking";

type Props = {
  seats: Seat[];
  /** Seat chosen by the passenger being seated now. */
  selected?: string;
  /** Seats already given to other passengers in this booking, with a short label (e.g. initials). */
  heldByParty: Map<string, string>;
  onSelect: (seatNumber: string) => void;
  /** Shown above the plane with the cabin buttons, such as the flight tabs while booking. */
  header?: React.ReactNode;
};

type Row = { row: number; cabin: Seat["cabin"]; seats: Seat[] };
type Cabin = { cabin: Seat["cabin"]; rows: Row[] };

function groupByRow(seats: Seat[]): Row[] {
  const rows = new Map<number, Row>();
  for (const seat of seats) {
    const row = rows.get(seat.row) ?? { row: seat.row, cabin: seat.cabin, seats: [] };
    row.seats.push(seat);
    rows.set(seat.row, row);
  }
  return [...rows.values()].sort((a, b) => a.row - b.row);
}

/** Runs of rows in the same cabin, front to back. */
function groupByCabin(rows: Row[]): Cabin[] {
  const cabins: Cabin[] = [];
  for (const row of rows) {
    const last = cabins.at(-1);
    if (last && last.cabin === row.cabin) last.rows.push(row);
    else cabins.push({ cabin: row.cabin, rows: [row] });
  }
  return cabins;
}

const cabinName = (cabin: Seat["cabin"]) => (cabin === "BUSINESS" ? "Business" : "Economy");

/**
 * The plane is drawn sideways, nose on the left, so a whole cabin fits in one screen height: each column is a row of seats,
 * read from the window at the top (F) to the window at the bottom (A), with the aisle before C in both cabins
 * (business F D | C A, economy F E D | C B A). When the rows are wider than the screen, arrows (and a swipe or the
 * scroll wheel) move along the plane, and the cabin buttons jump straight to a cabin.
 */
const AISLE_BEFORE = "C";

/** How far one arrow press moves: most of a screen, keeping a few rows in view so the traveller does not lose their place. */
const STEP = 0.8;

function scrollBehavior(): ScrollBehavior {
  return window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth";
}

export function SeatMap({ seats, selected, heldByParty, onSelect, header }: Props) {
  const rows = groupByRow(seats);
  const cabins = groupByCabin(rows);
  const scroller = useRef<HTMLDivElement>(null);
  const plane = useRef<HTMLDivElement>(null);
  const [edges, setEdges] = useState({ overflow: false, atStart: true, atEnd: true });
  // The outline is drawn to the size the seats actually take up, so it is measured rather than fixed.
  const [size, setSize] = useState({ width: 0, height: 0 });

  const measure = useCallback(() => {
    const el = scroller.current;
    if (!el) return;
    const max = el.scrollWidth - el.clientWidth;
    setEdges({ overflow: max > 4, atStart: el.scrollLeft <= 4, atEnd: el.scrollLeft >= max - 4 });
    const width = plane.current?.offsetWidth ?? 0;
    const height = plane.current?.offsetHeight ?? 0;
    setSize((current) => (current.width === width && current.height === height ? current : { width, height }));
  }, []);

  // Measured again whenever the map changes size: on first paint the page may not have laid it out yet (it streams in),
  // and the window can be resized or rotated.
  useEffect(() => {
    const el = scroller.current;
    measure();
    if (!el || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(measure);
    observer.observe(el);
    if (el.firstElementChild) observer.observe(el.firstElementChild);
    return () => observer.disconnect();
  }, [measure, seats.length]);

  // Opening the map on a seat already chosen (changing a seat, or coming back to this step) starts with that seat in view.
  const initialSeat = useRef(selected);
  useEffect(() => {
    const el = scroller.current;
    const column = initialSeat.current
      ? el?.querySelector<HTMLElement>(`[data-seat="${initialSeat.current}"]`)?.closest<HTMLElement>("[data-row]")
      : null;
    if (el && column) el.scrollLeft = column.offsetLeft - el.clientWidth / 2;
  }, []);

  function step(direction: 1 | -1) {
    const el = scroller.current;
    if (el) el.scrollBy({ left: direction * Math.round(el.clientWidth * STEP), behavior: scrollBehavior() });
  }

  function jumpTo(row: number) {
    const el = scroller.current;
    const column = el?.querySelector<HTMLElement>(`[data-row="${row}"]`);
    if (el && column) el.scrollTo({ left: Math.max(0, column.offsetLeft - 64), behavior: scrollBehavior() });
  }

  return (
    <div>
      <div className="relative">
        {/* The header (the flight tabs, when booking), the seat key and the cabin buttons sit in the open space just above
            the nose, ahead of the wing, so the plane starts right under them and the key is read before a seat is picked. */}
        <div className="absolute inset-x-0 z-20 flex -translate-y-full flex-col gap-3" style={{ top: PLANE.wingSpan - 10 }}>
          {/* First row: the flight tabs on the left, the seat key on the right. */}
          <div className="flex flex-wrap items-center justify-between gap-x-6 gap-y-2">
            {header}
            <ul aria-label="Seat key" className="flex flex-wrap gap-x-4 gap-y-2 text-xs text-muted">
              <KeyItem swatch="border-line bg-canvas" label="Available" />
              <KeyItem swatch="border-accent bg-accent" label="Your seat" />
              <KeyItem swatch="border-ink-soft bg-ink-soft" label="Your other passengers" />
              <KeyItem swatch="border-line bg-wash" label="Taken" />
            </ul>
          </div>
          {cabins.length > 1 && (
            <div className="flex flex-wrap gap-2">
              {cabins.map((cabin) => {
                const first = cabin.rows[0].row;
                const last = cabin.rows.at(-1)!.row;
                return (
                  <button
                    key={first}
                    type="button"
                    aria-label={`${cabinName(cabin.cabin)}, rows ${first}–${last}`}
                    onClick={() => jumpTo(first)}
                    className="rounded-full border border-line bg-white px-3 py-1.5 text-sm hover:border-accent"
                  >
                    {`${cabinName(cabin.cabin)} · rows ${first}–${last}`}
                  </button>
                );
              })}
            </div>
          )}
        </div>

        <div ref={scroller} onScroll={measure} className="relative overflow-x-auto overscroll-x-contain [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
          {/* The plane seen from above: the seats sit in the fuselage, with room left for the nose, the tail cone and
              the wings, and the outline is drawn behind them. */}
          <div
            ref={plane}
            className="relative w-max"
            style={{ padding: `${PLANE.wingSpan + PLANE.wall}px ${PLANE.tail}px ${PLANE.wingSpan + PLANE.wall}px ${PLANE.nose}px` }}
          >
            {size.width > 0 && <PlaneOutline width={size.width} height={size.height} />}
            <div className="relative flex items-stretch">
              {cabins.map((cabin, index) => (
                <div
                  key={cabin.rows[0].row}
                  className={`flex flex-col ${index > 0 ? "ml-5 border-l border-dashed border-line pl-5" : ""}`}
                >
                  <p className="mb-1 text-xs font-semibold tracking-wide text-muted">{cabinName(cabin.cabin)}</p>
                  <div className="flex flex-1 items-center gap-1">
                    {cabin.rows.map((row) => (
                      <div key={row.row} data-row={row.row} className="flex flex-col items-center gap-1">
                        <span className="font-mono text-xs text-muted" aria-hidden="true">
                          {row.row}
                        </span>
                        {[...row.seats]
                          .sort((a, b) => b.letter.localeCompare(a.letter))
                          .map((seat) => (
                            <Fragment key={seat.seatNumber}>
                              {seat.letter === AISLE_BEFORE && <span className="h-3" aria-hidden="true" />}
                              <SeatButton
                                seat={seat}
                                isSelected={selected === seat.seatNumber}
                                heldBy={heldByParty.get(seat.seatNumber)}
                                onSelect={onSelect}
                              />
                            </Fragment>
                          ))}
                      </div>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>

        {edges.overflow && (
          <>
            <ArrowButton direction="back" disabled={edges.atStart} onClick={() => step(-1)} />
            <ArrowButton direction="forward" disabled={edges.atEnd} onClick={() => step(1)} />
          </>
        )}
      </div>
    </div>
  );
}

function SeatButton({
  seat,
  isSelected,
  heldBy,
  onSelect,
}: {
  seat: Seat;
  isSelected: boolean;
  heldBy?: string;
  onSelect: (seatNumber: string) => void;
}) {
  const taken = seat.status === "BOOKED";
  const heldElsewhere = seat.status === "HELD"; // someone else is checking out with it
  const unavailable = taken || heldElsewhere || heldBy !== undefined;
  const cabin = seat.cabin === "BUSINESS" ? "business" : "economy";
  // A seat held by someone in this booking takes priority over "taken": when managing a booking, the
  // party's own seats are already booked, and they should read as the party's, not as a stranger's.
  const state = isSelected
    ? "selected"
    : heldBy
      ? "chosen for another passenger"
      : heldElsewhere
        ? "held by another traveller"
        : taken
          ? "taken"
          : "available";
  return (
    <button
      type="button"
      data-seat={seat.seatNumber}
      disabled={unavailable && !isSelected}
      aria-pressed={isSelected}
      aria-label={`Seat ${seat.seatNumber}, ${cabin}, ${state}`}
      onClick={() => onSelect(seat.seatNumber)}
      className={[
        "flex items-center justify-center rounded-md border text-xs font-medium",
        seat.cabin === "BUSINESS" ? "size-10" : "size-8",
        isSelected
          ? "border-accent bg-accent text-white"
          : heldBy
            ? "cursor-not-allowed border-ink-soft bg-ink-soft text-canvas"
            : heldElsewhere
              ? "cursor-not-allowed border-line bg-wash text-muted"
              : taken
                ? "cursor-not-allowed border-line bg-wash text-muted line-through"
                : "border-line bg-canvas hover:border-accent",
      ].join(" ")}
    >
      {isSelected ? seat.letter : (heldBy ?? (taken ? "×" : heldElsewhere ? "·" : seat.letter))}
    </button>
  );
}

function ArrowButton({
  direction,
  disabled,
  onClick,
}: {
  direction: "back" | "forward";
  disabled: boolean;
  onClick: () => void;
}) {
  const back = direction === "back";
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      aria-label={back ? "Show rows nearer the front" : "Show rows further back"}
      className={`absolute top-1/2 z-20 flex size-10 -translate-y-1/2 items-center justify-center rounded-full border border-line bg-white text-ink shadow-[0_2px_8px_rgb(15_27_45/0.18)] hover:border-accent hover:text-accent disabled:pointer-events-none disabled:opacity-0 ${
        back ? "left-2" : "right-2"
      }`}
    >
      <svg viewBox="0 0 20 20" className="size-5" aria-hidden="true">
        <path
          d={back ? "M12.5 4.5 7 10l5.5 5.5" : "M7.5 4.5 13 10l-5.5 5.5"}
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
    </button>
  );
}

/** Room around the seats for the parts of the plane, in pixels. */
const PLANE = { nose: 150, tail: 220, wingSpan: 118, wall: 10 };

/**
 * An airliner seen from above, in the style of the familiar aeroplane icon, drawn to the measured size of the seat area:
 * a rounded nose, a fuselage the seats sit in, long wings sweeping back to narrow rounded tips, a body narrowing to the
 * tail, and small swept tail fins. Every part is stroked first and then filled, so the parts merge into one continuous
 * outline with no lines where they join.
 */
function PlaneOutline({ width: w, height: h }: { width: number; height: number }) {
  const { nose, tail, wingSpan: span } = PLANE;
  const top = span; // fuselage top edge
  const bottom = h - span; // fuselage bottom edge
  const mid = h / 2;
  const half = mid - top;
  const tailStart = w - tail;
  const body = tailStart - nose;
  const edge = 6; // keeps the outline inside the drawing

  // A swept surface: root along the fuselage from rootX for rootChord, tip reaching yTip, rounded at the tip.
  const surface = (y0: number, yTip: number, rootX: number, rootChord: number, reach: number, tipChord: number) => {
    const out = Math.sign(yTip - y0) * 12; // the rounded tip bulges outwards
    const tipLead = rootX + reach;
    const tipTrail = tipLead + tipChord;
    return [
      `M${rootX} ${y0}`,
      `L${tipLead} ${yTip}`,
      `Q${(tipLead + tipTrail) / 2} ${yTip + out} ${tipTrail} ${yTip}`,
      `L${rootX + rootChord} ${y0}`,
      "Z",
    ].join(" ");
  };

  // Wings: a deep root a little ahead of the middle, swept well back so the tip sits behind the root.
  const wingX = nose + body * 0.34;
  const wingRoot = Math.min(420, body * 0.3);
  const wingReach = wingRoot * 1.15;
  const wings = [
    surface(top + half * 0.3, edge, wingX, wingRoot, wingReach, 46),
    surface(bottom - half * 0.3, h - edge, wingX, wingRoot, wingReach, 46),
  ];

  // Tail fins, where the body has started to narrow.
  const finX = tailStart + tail * 0.18;
  const finSpan = span * 0.55;
  const finInset = half * 0.3;
  const fins = [
    surface(top + finInset, top - finSpan, finX, 120, 105, 34),
    surface(bottom - finInset, bottom + finSpan, finX, 120, 105, 34),
  ];

  const fuselage = [
    `M${nose} ${top}`,
    `L${tailStart} ${top}`,
    `C${tailStart + tail * 0.55} ${top} ${w - edge} ${mid - half * 0.3} ${w - edge} ${mid - half * 0.12}`,
    `Q${w - edge + 3} ${mid} ${w - edge} ${mid + half * 0.12}`,
    `C${w - edge} ${mid + half * 0.3} ${tailStart + tail * 0.55} ${bottom} ${tailStart} ${bottom}`,
    `L${nose} ${bottom}`,
    `C${nose * 0.55} ${bottom} ${edge} ${mid + half * 0.18} ${edge} ${mid}`,
    `C${edge} ${mid - half * 0.18} ${nose * 0.55} ${top} ${nose} ${top}`,
    "Z",
  ].join(" ");

  const parts = [...wings, ...fins, fuselage];

  return (
    <svg width={w} height={h} viewBox={`0 0 ${w} ${h}`} className="pointer-events-none absolute inset-0" aria-hidden="true">
      {/* Outline: a wide stroke under everything, of which only the outer half stays visible. */}
      <g className="fill-none stroke-ink-soft/55" strokeWidth="5" strokeLinejoin="round">
        {parts.map((d) => (
          <path key={d} d={d} />
        ))}
      </g>
      <g className="fill-white">
        {parts.map((d) => (
          <path key={d} d={d} />
        ))}
      </g>
      {/* Windscreen. */}
      <path
        d={`M${nose * 0.42} ${mid - half * 0.26} Q${nose * 0.3} ${mid} ${nose * 0.42} ${mid + half * 0.26}`}
        className="fill-none stroke-ink-soft/60"
        strokeWidth="6"
        strokeLinecap="round"
      />
    </svg>
  );
}

function KeyItem({ swatch, label }: { swatch: string; label: string }) {
  return (
    <li className="flex items-center gap-2">
      <span className={`inline-block size-4 rounded border ${swatch}`} aria-hidden="true" />
      {label}
    </li>
  );
}
