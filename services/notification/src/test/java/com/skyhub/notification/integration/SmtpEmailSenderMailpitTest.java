package com.skyhub.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;

import com.skyhub.notification.application.EmailMessage;
import com.skyhub.notification.application.EmailUnavailableException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The SMTP adapter against a real mail server: Mailpit, the same image docker compose runs. The message goes over
 * SMTP and is read back over Mailpit's HTTP API, so what is checked is what a person would see in the inbox.
 */
class SmtpEmailSenderMailpitTest {

    private static final GenericContainer<?> MAILPIT = new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.21.8"))
            .withExposedPorts(1025, 8025)
            .waitingFor(Wait.forHttp("/livez").forPort(8025));
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    static void start() {
        MAILPIT.start();
    }

    @AfterAll
    static void stop() {
        MAILPIT.stop();
    }

    private static SmtpEmailSender senderTo(String host, int port) {
        JavaMailSenderImpl mail = new JavaMailSenderImpl();
        mail.setHost(host);
        mail.setPort(port);
        mail.setDefaultEncoding("UTF-8");
        Properties properties = mail.getJavaMailProperties();
        properties.put("mail.smtp.connectiontimeout", "2000");
        properties.put("mail.smtp.timeout", "3000");
        properties.put("mail.smtp.writetimeout", "3000");
        return new SmtpEmailSender(mail, "no-reply@skyhub.example");
    }

    private static JsonNode get(String path) throws Exception {
        String url = "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025) + path;
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
        return JSON.readTree(response.body());
    }

    /** The newest message whose subject contains {@code marker}, with its text. */
    private static JsonNode awaitMessage(String marker) {
        JsonNode[] found = new JsonNode[1];
        await().atMost(Duration.ofSeconds(15)).until(() -> {
            for (JsonNode summary : get("/api/v1/messages").get("messages")) {
                if (summary.get("Subject").asString().contains(marker)) {
                    found[0] = get("/api/v1/message/" + summary.get("ID").asString());
                    return true;
                }
            }
            return false;
        });
        return found[0];
    }

    @Test
    void should_deliver_the_email_over_smtp_with_the_right_sender_recipient_subject_and_text() {
        SmtpEmailSender sender = senderTo(MAILPIT.getHost(), MAILPIT.getMappedPort(1025));

        sender.send(new EmailMessage("priya@example.com", "Your SkyHub booking ZZ1111 is confirmed",
                "Your booking is confirmed.\n\nBooking reference: ZZ1111\n"));

        JsonNode mail = awaitMessage("ZZ1111");
        assertThat(mail.get("From").get("Address").asString()).isEqualTo("no-reply@skyhub.example");
        assertThat(mail.get("To").get(0).get("Address").asString()).isEqualTo("priya@example.com");
        assertThat(mail.get("Subject").asString()).isEqualTo("Your SkyHub booking ZZ1111 is confirmed");
        assertThat(mail.get("Text").asString()).contains("Booking reference: ZZ1111");
    }

    @Test
    void should_keep_names_with_accents_and_other_scripts_intact() {
        SmtpEmailSender sender = senderTo(MAILPIT.getHost(), MAILPIT.getMappedPort(1025));

        sender.send(new EmailMessage("jose@example.com", "Your SkyHub booking ZZ2222 is confirmed",
                "Passengers:\n  José Pérez\n  සුදේශ් පෙරේරා\n  Ünal Çelik\n"));

        String text = awaitMessage("ZZ2222").get("Text").asString();
        assertThat(text).contains("José Pérez").contains("සුදේශ් පෙරේරා").contains("Ünal Çelik");
    }

    @Test
    void should_report_a_mail_server_that_is_not_there_as_unavailable() {
        SmtpEmailSender sender = senderTo("127.0.0.1", 1); // nothing listens on port 1

        assertThatExceptionOfType(EmailUnavailableException.class)
                .isThrownBy(() -> sender.send(new EmailMessage("a@example.com", "Subject", "Body")));
    }
}
