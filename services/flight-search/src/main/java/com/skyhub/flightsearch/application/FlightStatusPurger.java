package com.skyhub.flightsearch.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Once an hour, drops flights that left more than a day ago, so the board stays small however long the service runs. */
@Component
class FlightStatusPurger {

    private final FlightStatusBoard board;

    FlightStatusPurger(FlightStatusBoard board) {
        this.board = board;
    }

    @Scheduled(fixedDelayString = "${skyhub.flight-status.purge-interval:PT1H}")
    void purge() {
        board.purge();
    }
}
