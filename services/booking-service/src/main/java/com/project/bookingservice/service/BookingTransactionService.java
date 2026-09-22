package com.project.bookingservice.service;

import com.project.bookingservice.entity.Booking;
import com.project.bookingservice.enums.BookingStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public interface BookingTransactionService {

    /**
     * Persists the initial IN_PROGRESS claim in its own committed TX.
     * After this returns, the idempotency row exists in the DB —
     * any concurrent duplicate will hit the unique constraint.
     */
    Booking createClaim(Booking booking);

    /**
     * Persists the finalized PENDING booking (with snapshot + passengers)
     * in its own committed TX.
     */
    Booking finalizeBooking(Booking booking);

    /**
     * Deletes the IN_PROGRESS claim if the reserve step fails.
     * Compensation — must be its own TX so it commits independently.
     */
    void deleteClaim(UUID bookingId);

    /**
     * Atomic state transition: UPDATE WHERE status = expectedStatus.
     * Returns true if exactly one row was updated (we "own" the transition).
     */
    boolean transitionStatus(UUID bookingId, BookingStatus expectedStatus, BookingStatus newStatus);

    /**
     * Atomic state transition for confirming a payment:
     * UPDATE WHERE status = PENDING. Sets status to CONFIRMED,
     * updates paymentId and confirmedAt.
     */
    boolean confirmPayment(UUID bookingId, UUID paymentId, LocalDateTime confirmedAt);
}
