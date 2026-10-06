package com.project.bookingservice.service;

public interface BookingExpirationService {

    /**
     * Expires all due active reservations using the single effective expiresAt.
     *
     * Eligible states:
     *   PENDING
     *   PAYMENT_PENDING
     *
     * Flow:
     *   active state -> EXPIRING -> release seats -> EXPIRED
     *
     * Processing is bounded and batched.
     */
    void expireDueBookings();
}