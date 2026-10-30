package com.skyhub.notification.integration;

import com.skyhub.notification.application.EmailMessage;
import com.skyhub.notification.application.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development adapter: "sends" an email by writing it to the log, for running with no mail server at all (set
 * {@code skyhub.notifications.email.mode=log}; the default is SMTP, ADR-0021). The address is masked; the body is only
 * logged at DEBUG, because it holds names.
 */
@Component
@ConditionalOnProperty(name = "skyhub.notifications.email.mode", havingValue = "log")
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(EmailMessage message) {
        log.atInfo().addKeyValue("email.to", message.maskedTo()).addKeyValue("email.subject", message.subject())
                .log("Email sent (log only)");
        log.atDebug().addKeyValue("email.body", message.body()).log("Email body");
    }
}
