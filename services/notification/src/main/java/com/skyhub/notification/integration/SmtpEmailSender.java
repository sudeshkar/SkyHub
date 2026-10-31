package com.skyhub.notification.integration;

import com.skyhub.notification.application.EmailMessage;
import com.skyhub.notification.application.EmailSender;
import com.skyhub.notification.application.EmailUnavailableException;
import com.skyhub.notification.application.HtmlBody;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Adapter: hands the email to an SMTP server (ADR-0021). Locally that is Mailpit, which catches every message and
 * shows it in a web page instead of delivering it; in production it would be the provider's SMTP relay. Anything
 * the server refuses or does not answer becomes {@link EmailUnavailableException}, which makes the event retry
 * instead of being recorded as sent. The message carries the plain text and an HTML version of it (ADR-0050). The failure message never contains the address or the body.
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
        try {
            MimeMessage mime = mail.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(message.to());
            helper.setSubject(message.subject());
            helper.setText(message.body(), HtmlBody.from(message.body()));
            mail.send(mime);
        } catch (MessagingException | MailException e) {
            throw new EmailUnavailableException("The mail server did not accept the message", e);
        }
    }
}
