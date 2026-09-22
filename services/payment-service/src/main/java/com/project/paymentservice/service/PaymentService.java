package com.project.paymentservice.service;

import com.project.paymentservice.dto.request.PaymentInitiationRequest;
import com.project.paymentservice.dto.response.PaymentInitiationResponse;

import java.util.UUID;

/**
 * Application-level orchestration contract for payment operations.
 *
 * This service owns the WHAT of the payment initiation flow:
 *
 *   - validate HTTP/business input
 *   - hash the idempotency key and request
 *   - fetch authoritative booking context
 *   - validate booking ownership, status, and financial snapshot
 *   - delegate short DB transactions to PaymentTransactionService
 *   - delegate provider communication to PaymentGateway
 *   - return a provider-neutral response to the caller
 *
 * This service must NOT:
 *   - contain @Transactional spanning network calls
 *   - read from SecurityContextHolder directly
 *   - know any provider-specific SDK types
 */
public interface PaymentService {

    /**
     * Initiates a payment for a booking.
     *
     * Idempotency semantics:
     *
     *   same idempotencyKey + same request body
     *     → returns the existing outcome (replay)
     *
     *   same idempotencyKey + different request body
     *     → 409 Conflict
     *
     * @param userId         authenticated user, extracted from the security context
     *                       by the controller — never read from inside this service
     * @param idempotencyKey raw Idempotency-Key header value; hashing is done
     *                       inside this service, not by the caller
     * @param request        validated payment initiation payload
     * @return provider-neutral payment initiation response
     */

    public PaymentInitiationResponse initiatePayment(
            UUID userId,
            String idempotencyKey,
            PaymentInitiationRequest request
    ) ;
}
