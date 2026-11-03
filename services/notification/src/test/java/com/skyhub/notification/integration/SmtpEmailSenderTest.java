package com.skyhub.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skyhub.notification.application.EmailMessage;
import com.skyhub.notification.application.EmailUnavailableException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class SmtpEmailSenderTest {

    private final JavaMailSender mail = mock(JavaMailSender.class);

    {
        when(mail.createMimeMessage()).thenAnswer(i -> new JavaMailSenderImpl().createMimeMessage());
    }
    private final SmtpEmailSender sender = new SmtpEmailSender(mail, "no-reply@skyhub.example");

    private static final EmailMessage MESSAGE = new EmailMessage("priya@example.com", "Your SkyHub booking ABC234 is confirmed",
            "Your booking is confirmed.");

    @Test
    void should_hand_the_message_to_the_mail_server_from_the_configured_address() throws Exception {
        sender.send(MESSAGE);

        MimeMessage sent = sentMessage();
        assertThat(sent.getFrom()[0].toString()).isEqualTo("no-reply@skyhub.example");
        assertThat(sent.getAllRecipients()[0].toString()).isEqualTo("priya@example.com");
        assertThat(sent.getSubject()).isEqualTo("Your SkyHub booking ABC234 is confirmed");
    }

    @Test
    void should_send_the_plain_text_and_an_html_version_of_the_same_message() throws Exception {
        sender.send(MESSAGE);

        String everything = textOf(sentMessage().getContent());
        assertThat(everything).contains("Your booking is confirmed.").contains("<p>Your booking is confirmed.</p>");
    }

    private MimeMessage sentMessage() {
        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail).send(sent.capture());
        return sent.getValue();
    }

    private static String textOf(Object content) throws Exception {
        if (content instanceof Multipart parts) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < parts.getCount(); i++) {
                out.append(textOf(parts.getBodyPart(i).getContent()));
            }
            return out.toString();
        }
        return String.valueOf(content);
    }

    @Test
    void should_report_a_refused_or_unreachable_server_as_unavailable_so_the_event_is_retried() {
        doThrow(new MailSendException("Mail server connection failed")).when(mail).send(any(MimeMessage.class));

        assertThatExceptionOfType(EmailUnavailableException.class).isThrownBy(() -> sender.send(MESSAGE))
                .withMessageContaining("mail server");
    }

    @Test
    void should_not_put_the_address_or_the_body_in_the_failure_message() {
        doThrow(new MailSendException("boom")).when(mail).send(any(MimeMessage.class));

        assertThatExceptionOfType(EmailUnavailableException.class).isThrownBy(() -> sender.send(MESSAGE))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("priya@example.com").doesNotContain("confirmed"));
    }

    // ---- attachments (ADR-0074) --------------------------------------------------------------------------------------------

    private static void collectAttachments(Object content, java.util.List<jakarta.mail.BodyPart> found) throws Exception {
        if (content instanceof Multipart parts) {
            for (int i = 0; i < parts.getCount(); i++) {
                jakarta.mail.BodyPart part = parts.getBodyPart(i);
                if (jakarta.mail.Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
                    found.add(part);
                } else {
                    collectAttachments(part.getContent(), found);
                }
            }
        }
    }

    @Test
    void should_attach_each_picture_with_its_name_and_type_beside_the_text_and_html() throws Exception {
        EmailMessage.Attachment one = new EmailMessage.Attachment("boarding-pass-SK501-11D.png", "image/png", new byte[] {1, 2, 3});
        EmailMessage.Attachment two = new EmailMessage.Attachment("boarding-pass-SK501-11E.png", "image/png", new byte[] {4, 5});

        sender.send(MESSAGE.withAttachments(java.util.List.of(one, two)));

        MimeMessage sent = sentMessage();
        sent.saveChanges();   // what the real sender does just before it sends: the headers are written
        java.util.List<jakarta.mail.BodyPart> attached = new java.util.ArrayList<>();
        collectAttachments(sent.getContent(), attached);
        assertThat(attached).extracting(part -> part.getFileName()).containsExactly("boarding-pass-SK501-11D.png", "boarding-pass-SK501-11E.png");
        assertThat(attached).allSatisfy(part -> assertThat(part.getContentType()).startsWith("image/png"));
        assertThat(attached.get(0).getDataHandler().getInputStream().readAllBytes()).containsExactly(1, 2, 3);
        assertThat(attached.get(1).getDataHandler().getInputStream().readAllBytes()).containsExactly(4, 5);
        assertThat(textOf(sent.getContent())).contains("Your booking is confirmed.").contains("<p>Your booking is confirmed.</p>");
    }

    @Test
    void should_attach_nothing_to_a_message_that_has_no_pictures() throws Exception {
        sender.send(MESSAGE);

        java.util.List<jakarta.mail.BodyPart> attached = new java.util.ArrayList<>();
        collectAttachments(sentMessage().getContent(), attached);
        assertThat(attached).isEmpty();
    }
}
