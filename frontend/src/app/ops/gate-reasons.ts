import type { GateRefusal } from "./actions";

/** What the gate agent is told when a pass is refused: what to do, never who the pass was for (ADR-0056). */
export const REFUSALS: Record<GateRefusal, string> = {
  BAD_SIGNATURE: "This is not a SkyHub boarding pass, or it has been changed. Do not board.",
  UNKNOWN_BOOKING: "This pass has no booking behind it. Do not board.",
  BOOKING_CANCELLED: "This booking was cancelled. Do not board.",
  FLIGHT_CANCELLED: "This flight was cancelled. Do not board.",
  NOT_CHECKED_IN: "This passenger is not checked in (they may have checked out). Send them to check in.",
  SUPERSEDED: "This is an old pass; a newer one was issued after a seat change or a new check-in. Ask for the current pass.",
  ALREADY_BOARDED: "This passenger has already boarded. Do not board them again.",
  BOARDING_NOT_OPEN: "Boarding has not opened for this flight yet. Do not board them yet; try again when boarding opens.",
  BOARDING_CLOSED: "Boarding has closed for this flight. Do not board; send the passenger to the desk.",
  BOARDING_STILL_OPEN: "Boarding is still open for this flight: use the ordinary Board passenger button, not late boarding. Do not board them late.",
  FLIGHT_DEPARTED: "This flight is due to have left. Do not board; send the passenger to the desk to be rebooked.",
};
