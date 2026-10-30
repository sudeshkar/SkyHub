package com.skyhub.booking.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Creates the first administrator at startup, once, when there are no staff accounts and
 * {@code SKYHUB_BOOTSTRAP_ADMIN_USERNAME} / {@code SKYHUB_BOOTSTRAP_ADMIN_PASSWORD} are set (ADR-0017). Once any
 * account exists the settings are ignored, so leaving them in place later changes nothing.
 */
@Component
class StaffBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StaffBootstrap.class);

    private final StaffService staff;
    private final String username;
    private final String password;

    StaffBootstrap(StaffService staff,
                   @Value("${skyhub.bootstrap.admin-username:}") String username,
                   @Value("${skyhub.bootstrap.admin-password:}") String password) {
        this.staff = staff;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (staff.ensureFirstAdmin(username, password)) {
            log.info("Created the first administrator account");
        }
    }
}
