package com.project.paymentservice.service;

import com.project.paymentservice.enums.PaymentMethodType;
import com.project.paymentservice.enums.PaymentProvider;

import java.util.UUID;

public interface PaymentRequestHashService {

    /**
     * Generates a deterministic SHA-256 hash for a payment initiation request.
     * 
     * Used to distinguish between a replay of the same request
     * (same Idempotency-Key + same hash) and a conflict
     * (same Idempotency-Key + different hash).
     */
    String generateHash(
            UUID bookingId,
            PaymentProvider provider,
            PaymentMethodType paymentMethod
    );

    String hashIdempotencyKey(String idempotencyKey);
}
