package com.skyhub.booking.application;

import com.skyhub.booking.application.view.PnrView;

/** {@code created} is false when an Idempotency-Key was replayed and the original PNR returned. */
public record BookingResult(PnrView pnr, boolean created) {
}
