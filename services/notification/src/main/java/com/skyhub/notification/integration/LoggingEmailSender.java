package com.skyhub.notification.integration;

import com.skyhub.notification.application.EmailMessage;
import com.skyhub.notification.application.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Development adapter: "sends" an email by writing it to the log. It lets the whole path run and be tested without
 * a mail server. The address is masked; the body is only logged at DEBUG, because it holds names. A real SMTP
 * adapter replaces this class and nothing else (ADR-0019).
 */
@Component
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(EmailMessage message) {
        log.atInfo().addKeyValue("email.to", message.maskedTo()).addKeyValue("email.subject", message.subject())
                .log("Email sent (log only)");
        log.atDebug().addKeyValue("email.body", message.body()).log("Email body");
    }
}
