/** A simple line icon for each operations tool, drawn in the current text colour. */

export type ToolId = "record" | "withdraw" | "move" | "gate" | "late" | "boarded" | "undo";

type IconProps = { className?: string };

function Svg({ className, children }: IconProps & { children: React.ReactNode }) {
  return (
    <svg
      viewBox="0 0 24 24"
      className={className}
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {children}
    </svg>
  );
}

export const TOOL_ICONS: Record<ToolId, (props: IconProps) => React.ReactNode> = {
  // A warning triangle: something is wrong with a flight.
  record: (p) => (
    <Svg {...p}>
      <path d="M12 3.5 2.8 19.5h18.4L12 3.5Z" />
      <path d="M12 10v4.2M12 17h.01" />
    </Svg>
  ),
  // A circular arrow: take it back.
  withdraw: (p) => (
    <Svg {...p}>
      <path d="M4 12a8 8 0 1 0 2.4-5.7L4 8.5" />
      <path d="M4 4v4.5h4.5" />
    </Svg>
  ),
  // Two arrows swapping: move travellers to another flight.
  move: (p) => (
    <Svg {...p}>
      <path d="M4 8h13l-3.5-3.5M20 16H7l3.5 3.5" />
    </Svg>
  ),
  // A scanner frame: scan boarding passes.
  gate: (p) => (
    <Svg {...p}>
      <path d="M4 8V5.5A1.5 1.5 0 0 1 5.5 4H8M16 4h2.5A1.5 1.5 0 0 1 20 5.5V8M20 16v2.5a1.5 1.5 0 0 1-1.5 1.5H16M8 20H5.5A1.5 1.5 0 0 1 4 18.5V16" />
      <path d="M4 12h16" />
    </Svg>
  ),
  // A clock: late.
  late: (p) => (
    <Svg {...p}>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 7.5V12l3 2" />
    </Svg>
  ),
  // People with a tick: who is on board.
  boarded: (p) => (
    <Svg {...p}>
      <circle cx="9" cy="8" r="3.2" />
      <path d="M3.5 19a5.5 5.5 0 0 1 11 0" />
      <path d="m15.5 11.5 2 2 3.5-4" />
    </Svg>
  ),
  // A cross in a circle: undo a mistake.
  undo: (p) => (
    <Svg {...p}>
      <circle cx="12" cy="12" r="8.5" />
      <path d="m9 9 6 6M15 9l-6 6" />
    </Svg>
  ),
};
