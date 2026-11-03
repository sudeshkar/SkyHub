package com.skyhub.notification.application;

/**
 * An email ready to send. The address and the body are personal data, so {@link #toString()} shows neither. {@code attachments} are
 * pictures or files that go with it (ADR-0074); a message with none has an empty list, never null. Their contents and names are never
 * printed.
 */
public record EmailMessage(String to, String subject, String body, java.util.List<Attachment> attachments) {

    /** A file that travels with the email. {@code content} is the file itself and is never printed. */
    public record Attachment(String fileName, String contentType, byte[] content) {

        @Override
        public String toString() {
            return "Attachment[" + content.length + " bytes]";
        }
    }

    public EmailMessage {
        attachments = attachments == null ? java.util.List.of() : java.util.List.copyOf(attachments);
    }

    /** A message with no attachments, which is almost every message. */
    public EmailMessage(String to, String subject, String body) {
        this(to, subject, body, java.util.List.of());
    }

    public EmailMessage withAttachments(java.util.List<Attachment> attached) {
        return new EmailMessage(to, subject, body, attached);
    }

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
        return "EmailMessage[to=" + maskedTo() + ", subject=" + subject + ", attachments=" + attachments.size() + "]";
    }
}
