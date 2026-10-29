package com.skyhub.booking.domain;

/** Produces PNR locators. An interface so tests can use a fixed locator. */
@FunctionalInterface
public interface LocatorGenerator {

    String next();
}
