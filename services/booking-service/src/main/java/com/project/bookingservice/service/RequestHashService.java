package com.project.bookingservice.service;

import com.project.bookingservice.dto.request.BookingRequest;

public interface RequestHashService {

    /**
     * Computes a deterministic SHA-256 fingerprint of the booking request.
     *
     * Normalization:
     *   - strings trimmed
     *   - nationality / passport uppercased
     *   - passengers sorted by passportNumber then lastName
     *
     * Same request content = same hash (idempotency fingerprint).
     * Same key + different hash = 409 Conflict.
     */
    String computeHash(BookingRequest request);
}
