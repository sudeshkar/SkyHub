package com.skyhub.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.skyhub.notification.application.EmailMessage;
import com.skyhub.notification.application.EmailUnavailableException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class SmtpEmailSenderTest {

    private final JavaMailSender mail = mock(JavaMailSender.class);
    private final SmtpEmailSender sender = new SmtpEmailSender(mail, "no-reply@skyhub.example");

    private static final EmailMessage MESSAGE = new EmailMessage("priya@example.com", "Your SkyHub booking ABC234 is confirmed",
            "Your booking is confirmed.");

    @Test
    void should_hand_the_message_to_the_mail_server_from_the_configured_address() {
        sender.send(MESSAGE);

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(sent.capture());
        assertThat(sent.getValue().getFrom()).isEqualTo("no-reply@skyhub.example");
        assertThat(sent.getValue().getTo()).containsExactly("priya@example.com");
        assertThat(sent.getValue().getSubject()).isEqualTo("Your SkyHub booking ABC234 is confirmed");
        assertThat(sent.getValue().getText()).isEqualTo("Your booking is confirmed.");
    }

    @Test
    void should_report_a_refused_or_unreachable_server_as_unavailable_so_the_event_is_retried() {
        doThrow(new MailSendException("Mail server connection failed")).when(mail).send(any(SimpleMailMessage.class));

        assertThatExceptionOfType(EmailUnavailableException.class).isThrownBy(() -> sender.send(MESSAGE))
                .withMessageContaining("mail server");
    }

    @Test
    void should_not_put_the_address_or_the_body_in_the_failure_message() {
        doThrow(new MailSendException("boom")).when(mail).send(any(SimpleMailMessage.class));

        assertThatExceptionOfType(EmailUnavailableException.class).isThrownBy(() -> sender.send(MESSAGE))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("priya@example.com").doesNotContain("confirmed"));
    }
}
