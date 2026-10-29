package com.skyhub.booking.application;

/**
 * Port: issues the credential that authorises managing one booking. The application owns this
 * interface; the JWT implementation lives in the security package, so the use cases never depend on a
 * token format.
 */
@FunctionalInterface
public interface ManageTokenIssuer {

    ManageToken issue(String locator);
}
