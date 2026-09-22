package com.project.paymentservice.enums;

/**
 * Result of confirming a booking after a successful payment.
 * Used to determine the response sent back to the end user.
 */
public enum BookingConfirmationStatus {
    NOT_STARTED,
    PENDING,
    CONFIRMED,
    REJECTED
}
