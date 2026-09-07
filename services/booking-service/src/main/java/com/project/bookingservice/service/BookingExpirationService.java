package com.project.bookingservice.service;

public interface BookingExpirationService {

    /**
     * Scans PENDING bookings past their expiresAt, transitions them atomically:
     *   PENDING → EXPIRING → (release seats) → EXPIRED
     *
     * Runs in batches to avoid loading the full table into memory.
     * Called exclusively by the @Scheduled cleanup job.
     */
    void expirePendingBookings();
}
