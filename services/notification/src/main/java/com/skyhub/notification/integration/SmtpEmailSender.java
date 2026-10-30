package com.skyhub.notification.integration;

import com.skyhub.notification.application.EmailMessage;
import com.skyhub.notification.application.EmailSender;
import com.skyhub.notification.application.EmailUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Adapter: hands the email to an SMTP server (ADR-0021). Locally that is Mailpit, which catches every message and
 * shows it in a web page instead of delivering it; in production it would be the provider's SMTP relay. Anything
 * the server refuses or does not answer becomes {@link EmailUnavailableException}, which makes the event retry
 * instead of being recorded as sent. The failure message never contains the address or the body.
 */
@Component
@ConditionalOnProperty(name = "skyhub.notifications.email.mode", havingValue = "smtp", matchIfMissing = true)
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mail;
    private final String from;

    public SmtpEmailSender(JavaMailSender mail, @Value("${skyhub.notifications.email.from}") String from) {
        this.mail = mail;
        this.from = from;
    }

    @Override
    public void send(EmailMessage message) {
        SimpleMailMessage mime = new SimpleMailMessage();
        mime.setFrom(from);
        mime.setTo(message.to());
        mime.setSubject(message.subject());
        mime.setText(message.body());
        try {
            mail.send(mime);
        } catch (MailException e) {
            throw new EmailUnavailableException("The mail server did not accept the message", e);
        }
    }
}
