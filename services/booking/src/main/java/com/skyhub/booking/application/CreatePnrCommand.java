package com.skyhub.booking.application;

import com.skyhub.booking.domain.PnrSegment;
import java.math.BigDecimal;
import java.util.List;

/**
 * {@code holdId} is optional: with it the seats were held earlier, without it they are booked directly.
 * {@code paymentToken} is what the payment provider issued to the browser. {@code expectedAmount} is optional:
 * the price the traveller was shown, checked against the server's own price.
 */
public record CreatePnrCommand(String contactEmail, List<PnrSegment> segments, List<PassengerCommand> passengers,
                               String holdId, String paymentToken, BigDecimal expectedAmount) {
}
