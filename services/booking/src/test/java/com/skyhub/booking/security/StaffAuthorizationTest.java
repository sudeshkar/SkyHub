package com.skyhub.booking.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.skyhub.booking.application.StaffToken;
import com.skyhub.booking.domain.StaffRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/** The staff rules against real tokens: scope and role must both be right, and a booking token never counts. */
class StaffAuthorizationTest {

    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final SecretKey key = ManageTokenConfig.parseKey("AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=");
    private final JwtEncoder encoder = ManageTokenConfig.encoder(key);
    private final JwtDecoder decoder = ManageTokenConfig.decoder(key, CLOCK);
    private final JwtStaffTokenIssuer staffTokens = new JwtStaffTokenIssuer(encoder, CLOCK, Duration.ofMinutes(30));
    private final JwtManageTokenIssuer manageTokens = new JwtManageTokenIssuer(encoder, CLOCK, Duration.ofMinutes(15));

    private Authentication authenticate(String tokenValue) {
        Jwt jwt = decoder.decode(tokenValue);
        return new JwtAuthenticationConverter().convert(jwt);
    }

    private static boolean granted(StaffAuthorization rule, Authentication auth) {
        return rule.authorize(() -> auth, new RequestAuthorizationContext(new MockHttpServletRequest())).isGranted();
    }

    @Test
    void should_issue_a_thirty_minute_token_for_the_username_with_scope_and_role() {
        StaffToken token = staffTokens.issue("priya.ops", StaffRole.OPS);
        Jwt jwt = decoder.decode(token.value());

        assertThat(token.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        assertThat(jwt.getSubject()).isEqualTo("priya.ops");
        assertThat(jwt.<String>getClaim("scope")).isEqualTo("staff");
        assertThat(jwt.<String>getClaim("role")).isEqualTo("OPS");
        assertThat(token.toString()).doesNotContain(token.value());
    }

    @Test
    void should_let_ops_record_disruptions_but_not_create_staff() {
        Authentication ops = authenticate(staffTokens.issue("priya.ops", StaffRole.OPS).value());

        assertThat(granted(StaffAuthorization.recordsDisruptions(), ops)).isTrue();
        assertThat(granted(StaffAuthorization.createsStaff(), ops)).isFalse();
    }

    @Test
    void should_let_both_roles_check_a_boarding_pass_but_not_a_traveller_or_nobody() {
        Authentication ops = authenticate(staffTokens.issue("priya.ops", StaffRole.OPS).value());
        Authentication admin = authenticate(staffTokens.issue("root.admin", StaffRole.ADMIN).value());
        Authentication traveller = authenticate(manageTokens.issue("ABC234", 0).value());

        assertThat(granted(StaffAuthorization.verifiesBoardingPasses(), ops)).isTrue();
        assertThat(granted(StaffAuthorization.verifiesBoardingPasses(), admin)).isTrue();
        assertThat(granted(StaffAuthorization.verifiesBoardingPasses(), traveller)).isFalse();
        assertThat(granted(StaffAuthorization.verifiesBoardingPasses(), null)).isFalse();
    }

    @Test
    void should_let_a_gate_agent_check_and_board_a_pass_and_do_nothing_else() {
        Authentication gate = authenticate(staffTokens.issue("gate.one", StaffRole.GATE).value());

        assertThat(granted(StaffAuthorization.verifiesBoardingPasses(), gate)).isTrue();
        assertThat(granted(StaffAuthorization.boardsPassengers(), gate)).isTrue();
        assertThat(granted(StaffAuthorization.viewsAffectedBookings(), gate)).isFalse();
        assertThat(granted(StaffAuthorization.rebooks(), gate)).isFalse();
        assertThat(granted(StaffAuthorization.recordsDisruptions(), gate)).isFalse();
        assertThat(granted(StaffAuthorization.createsStaff(), gate)).isFalse();
    }

    @Test
    void should_let_operations_board_passengers_but_not_a_traveller_or_nobody() {
        Authentication ops = authenticate(staffTokens.issue("priya.ops", StaffRole.OPS).value());
        Authentication traveller = authenticate(manageTokens.issue("ABC234", 0).value());

        assertThat(granted(StaffAuthorization.boardsPassengers(), ops)).isTrue();
        assertThat(granted(StaffAuthorization.boardsPassengers(), traveller)).isFalse();
        assertThat(granted(StaffAuthorization.boardsPassengers(), null)).isFalse();
    }

    @Test
    void should_let_an_admin_do_both() {
        Authentication admin = authenticate(staffTokens.issue("root.admin", StaffRole.ADMIN).value());

        assertThat(granted(StaffAuthorization.recordsDisruptions(), admin)).isTrue();
        assertThat(granted(StaffAuthorization.createsStaff(), admin)).isTrue();
    }

    @Test
    void should_refuse_a_booking_token_even_when_its_subject_looks_like_a_staff_name() {
        Authentication traveller = authenticate(manageTokens.issue("priya.ops", 0).value());

        assertThat(granted(StaffAuthorization.recordsDisruptions(), traveller)).isFalse();
        assertThat(granted(StaffAuthorization.createsStaff(), traveller)).isFalse();
    }

    @Test
    void should_refuse_a_staff_token_on_a_booking() {
        Authentication staff = authenticate(staffTokens.issue("root.admin", StaffRole.ADMIN).value());
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestAuthorizationContext context = new RequestAuthorizationContext(request, java.util.Map.of("locator", "root.admin"));

        assertThat(new OwnsBookingAuthorization().authorize(() -> staff, context).isGranted()).isFalse();
    }

    @Test
    void should_refuse_anonymous_and_non_token_authentication() {
        assertThat(granted(StaffAuthorization.recordsDisruptions(), null)).isFalse();
        assertThat(granted(StaffAuthorization.recordsDisruptions(),
                new TestingAuthenticationToken("x", "y", new SimpleGrantedAuthority("SCOPE_staff")))).isFalse();
    }
}
