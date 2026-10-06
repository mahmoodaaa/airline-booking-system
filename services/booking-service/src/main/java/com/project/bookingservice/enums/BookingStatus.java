package com.project.bookingservice.enums;

public enum BookingStatus {
    IN_PROGRESS,      // Temporary claim during booking creation (before reserve succeeds)
    PENDING,          // Reserve succeeded, awaiting payment (TTL: expiresAt)
    PAYMENT_PENDING,
    FAILED,           // Ambiguous failure during reservation, requires manual reconciliation
    CANCELLING,       // Atomic intermediate: user cancel claimed, releaseSeats in progress
    CANCELLED,        // Seats released, user cancelled
    EXPIRING,         // Atomic intermediate: scheduler claimed expiry, releaseSeats in progress
    EXPIRED,          // TTL or payment hold exceeded, seats released by scheduler
    CONFIRMED,        // Payment succeeded and booking finalized
    COMPENSATION_FAILED //reserve نجح + finalize booking فشل  + compensation release فشل
}
