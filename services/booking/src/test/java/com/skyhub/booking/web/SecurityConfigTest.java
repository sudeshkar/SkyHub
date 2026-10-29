package com.skyhub.booking.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skyhub.booking.application.BookingService;
import com.skyhub.booking.config.SecurityConfig;
import com.skyhub.booking.domain.PnrNotFoundException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PnrsController.class)
@Import(SecurityConfig.class)
class SecurityConfigTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BookingService bookingService;

    @Test
    void should_deny_endpoints_outside_the_contract() throws Exception {
        mvc.perform(get("/api/v1/admin/pnrs")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/pnrs/ABC234")).andExpect(status().isForbidden());
    }

    @Test
    void should_send_security_headers_and_no_session_cookie() throws Exception {
        Mockito.when(bookingService.findPnr("ABC234", "Perera")).thenThrow(new PnrNotFoundException("not found"));

        mvc.perform(get("/api/v1/pnrs/ABC234").param("lastName", "Perera"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }
}
