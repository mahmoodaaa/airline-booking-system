package com.project.bookingservice.exception;

/**
 * Thrown when:
 *  - Same Idempotency-Key is reused with a different request payload → 409
 *  - Same Idempotency-Key is submitted while first request is still IN_PROGRESS → 409
 */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String message) {
        super(message);
    }
}
