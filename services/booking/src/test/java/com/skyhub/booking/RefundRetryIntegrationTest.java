package com.skyhub.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.skyhub.booking.application.PaymentUnavailableException;
import com.skyhub.booking.application.RefundBackoff;
import com.skyhub.booking.application.RefundRetrier;
import com.skyhub.booking.application.RefundSource;
import com.skyhub.booking.application.UnrefundedCharges;
import com.skyhub.booking.domain.Money;
import com.skyhub.booking.integration.MockPaymentGateway;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Refunds that were not paid the first time are paid later (ADR-0030), on real PostgreSQL. The timer is off in tests, and
 * {@code sweep(Instant)} asks for the refunds that will be due later, so no test waits.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, TestFlightCatalog.class})
class RefundRetryIntegrationTest {

    private static final Instant LATER = Instant.now().plus(Duration.ofHours(1));

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RefundRetrier retrier;

    @Autowired
    private UnrefundedCharges unrefunded;

    @Autowired
    private List<RefundSource> sources;

    @MockitoSpyBean
    private MockPaymentGateway gateway;

    private String charge() {
        return gateway.charge("tok_visa", Money.of("100.00", "USD"), "key-" + UUID.randomUUID()).id();
    }

    private boolean marked(String chargeId) {
        return jdbc.queryForObject("SELECT refunded_at IS NOT NULL FROM unrefunded_charge WHERE charge_id = ?", Boolean.class, chargeId);
    }

    private int attempts(String chargeId) {
        return jdbc.queryForObject("SELECT attempts FROM unrefunded_charge WHERE charge_id = ?", Integer.class, chargeId);
    }

    @Test
    void should_give_back_a_charge_that_was_taken_for_a_failed_booking_and_do_it_only_once() {
        String chargeId = charge();
        unrefunded.record(chargeId, Money.of("100.00", "USD"), "SeatUnavailableException", Instant.now());

        retrier.sweep();   // not due yet: the first retry waits five minutes
        assertThat(gateway.isRefunded(chargeId)).isFalse();

        retrier.sweep(LATER);
        assertThat(gateway.isRefunded(chargeId)).isTrue();
        assertThat(marked(chargeId)).isTrue();

        int refunds = gateway.refundCount();
        retrier.sweep(LATER.plus(Duration.ofDays(1)));
        assertThat(gateway.refundCount()).isEqualTo(refunds);   // it is finished: nothing is asked of the provider again
    }

    @Test
    void should_write_a_charge_down_once_however_often_it_is_recorded() {
        String chargeId = charge();

        unrefunded.record(chargeId, Money.of("100.00", "USD"), "x", Instant.now());
        unrefunded.record(chargeId, Money.of("100.00", "USD"), "x", Instant.now());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM unrefunded_charge WHERE charge_id = ?", Integer.class, chargeId)).isEqualTo(1);
    }

    @Test
    void should_back_off_while_the_provider_is_down_and_stop_after_the_last_attempt() {
        String chargeId = charge();
        unrefunded.record(chargeId, Money.of("100.00", "USD"), "x", Instant.now());
        doThrow(new PaymentUnavailableException("no answer", null)).when(gateway).refund(chargeId);

        Instant when = LATER;
        for (int i = 0; i < RefundBackoff.MAX_ATTEMPTS + 3; i++) {
            retrier.sweep(when);
            when = when.plus(Duration.ofHours(5));   // longer than the longest wait, so every sweep finds it due if it is allowed
        }

        assertThat(attempts(chargeId)).isEqualTo(RefundBackoff.MAX_ATTEMPTS);   // it stopped by itself
        assertThat(gateway.isRefunded(chargeId)).isFalse();
    }

    @Test
    void should_not_ask_again_before_the_wait_is_over() {
        String chargeId = charge();
        unrefunded.record(chargeId, Money.of("100.00", "USD"), "x", Instant.now());
        doThrow(new PaymentUnavailableException("no answer", null)).when(gateway).refund(chargeId);

        retrier.sweep(LATER);
        retrier.sweep(LATER.plus(Duration.ofMinutes(1)));   // far less than the wait after a first failure

        assertThat(attempts(chargeId)).isEqualTo(1);
    }

    @Test
    void should_pay_a_traveller_whose_cancellation_refund_failed_without_waiting_for_them_to_press_cancel_again() throws Exception {
        String locator = book("SK777", "2027-06-01", "14D");
        doThrow(new PaymentUnavailableException("The payment provider did not answer", null))
                .doCallRealMethod().when(gateway).refund(anyString());
        mvc.perform(post("/api/v1/pnrs/{l}/cancellation", locator).header("Authorization", tokenFor(locator))).andExpect(status().isOk());
        assertThat(paymentStatus(locator)).isEqualTo("REFUND_DUE");

        long before = refundEmails();

        retrier.sweep(LATER);

        assertThat(paymentStatus(locator)).isEqualTo("REFUNDED");
        assertThat(refundEmails() - before).isEqualTo(1);   // the traveller is told the late refund was paid
        retrier.sweep(LATER.plus(Duration.ofDays(1)));
        assertThat(refundEmails() - before).isEqualTo(1);   // and only once
    }

    @Test
    void should_not_tell_the_traveller_again_when_they_paid_themselves_by_cancelling_again_before_the_job_ran() throws Exception {
        String locator = book("SK777", "2027-06-02", "14D");
        doThrow(new PaymentUnavailableException("The payment provider did not answer", null))
                .doCallRealMethod().when(gateway).refund(anyString());
        mvc.perform(post("/api/v1/pnrs/{l}/cancellation", locator).header("Authorization", tokenFor(locator))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/pnrs/{l}/cancellation", locator).header("Authorization", tokenFor(locator))).andExpect(status().isOk());
        assertThat(paymentStatus(locator)).isEqualTo("REFUNDED");
        long before = refundEmails();

        retrier.sweep(LATER);

        assertThat(refundEmails()).isEqualTo(before);   // nothing was still owed, so nothing is announced
    }

    @Test
    void should_hand_each_refund_to_only_one_of_two_instances_working_at_once() throws Exception {
        List<String> charges = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String chargeId = charge();
            charges.add(chargeId);
            unrefunded.record(chargeId, Money.of("100.00", "USD"), "x", Instant.now());
        }
        RefundSource source = sources.stream().filter(s -> s.name().equals("failed-booking")).findFirst().orElseThrow();

        var first = CompletableFuture.supplyAsync(() -> source.claimDue(LATER, 100));
        var second = CompletableFuture.supplyAsync(() -> source.claimDue(LATER, 100));
        List<Long> ids = new ArrayList<>();
        first.get().forEach(p -> ids.add(p.id()));
        second.get().forEach(p -> ids.add(p.id()));

        assertThat(ids).doesNotHaveDuplicates();
        assertThat(ids.size()).isGreaterThanOrEqualTo(20);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private long refundEmails() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE event_type = 'booking.refund-completed'", Long.class);
    }

    private String paymentStatus(String locator) {
        return jdbc.queryForObject("SELECT status FROM payment WHERE locator = ?", String.class, locator);
    }

    private String tokenFor(String locator) throws Exception {
        String response = mvc.perform(post("/api/v1/pnrs/{l}/sessions", locator).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lastName\":\"Perera\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.<String>read(response, "$.accessToken");
    }

    private String book(String flight, String date, String seat) throws Exception {
        String body = """
                {"paymentToken":"tok_visa","contactEmail":"priya@example.com",
                 "segments":[{"flightNumber":"%s","departureDate":"%s"}],
                 "passengers":[{"firstName":"Priya","lastName":"Perera","seats":["%s"]}]}""".formatted(flight, date, seat);
        String response = mvc.perform(post("/api/v1/pnrs").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.locator");
    }
}
