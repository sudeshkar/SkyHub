package com.skyhub.notification.application;

/** An email ready to send. The address and the body are personal data, so {@link #toString()} shows neither. */
public record EmailMessage(String to, String subject, String body) {

    /** "priya@example.com" becomes "p****@example.com": enough to recognise, not enough to harvest. */
    public static String mask(String address) {
        int at = address.indexOf('@');
        if (at < 1) {
            return "****";
        }
        String visible = at == 1 ? "" : address.substring(0, 1);
        return visible + "****" + address.substring(at);
    }

    public String maskedTo() {
        return mask(to);
    }

    @Override
    public String toString() {
        return "EmailMessage[to=" + maskedTo() + ", subject=" + subject + "]";
    }
}
