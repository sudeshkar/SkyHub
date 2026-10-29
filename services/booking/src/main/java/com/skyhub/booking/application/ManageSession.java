package com.skyhub.booking.application;

import com.skyhub.booking.application.view.PnrView;

public record ManageSession(ManageToken token, PnrView pnr) {
}
