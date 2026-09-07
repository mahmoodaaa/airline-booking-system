package com.project.bookingservice.enums;

public enum BookingStatus {
    IN_PROGRESS,  // Temporary claim during booking creation (before reserve succeeds)
    PENDING,      // Reserve succeeded, awaiting payment (TTL: 10 minutes)
    FAILED,       // Ambiguous failure during reservation, requires manual reconciliation
    CANCELLING,   // Atomic intermediate: user cancel claimed, releaseSeats in progress
    CANCELLED,    // Seats released, user cancelled
    EXPIRING,     // Atomic intermediate: scheduler claimed expiry, releaseSeats in progress
    EXPIRED,      // TTL exceeded, seats released by scheduler
    CONFIRMED,    // Payment succeeded (Sprint 5)
    COMPENSATION_FAILED //reserve نجح + finalize booking فشل  + compensation release فشل
}
