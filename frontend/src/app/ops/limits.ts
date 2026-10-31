/**
 * The most moves one server call carries: the booking service's bulk limit (ADR-0035). The page sends longer lists as several
 * calls, so no single request runs for long. Lives apart from actions.ts because a "use server" file may only export async functions.
 */
export const MAX_MOVES_PER_CALL = 50;
