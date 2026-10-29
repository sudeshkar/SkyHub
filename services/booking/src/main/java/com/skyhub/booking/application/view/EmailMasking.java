package com.skyhub.booking.application.view;

/** "priya@example.com" → "p****@example.com". Enough for a passenger to recognise, not to harvest. */
final class EmailMasking {

    private EmailMasking() {
    }

    static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "****";
        }
        String visible = at == 1 ? "" : email.substring(0, 1);
        return visible + "****" + email.substring(at);
    }
}
