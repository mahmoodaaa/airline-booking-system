package com.project.paymentservice.enums;

/**
 * Lifecycle status of a single PaymentAttempt (one Stripe Checkout Session).
 *
 * CRITICAL semantics:
 *   UNKNOWN  — we never received a confirmed provider outcome (network timeout,
 *               response lost, etc.). Provider truth is unresolved; do NOT
 *               treat as terminal until verified via Stripe lookup.
 *
 *   EXPIRED  — the Stripe Checkout Session window elapsed (Stripe confirmed it
 *               expired, or we reconciled it so). This is a Stripe-business
 *               state, NOT a local timeout label.
 *
 * A local network timeout must always map to UNKNOWN, never to EXPIRED.
 */
public enum PaymentAttemptStatus {

    /**
     * Attempt record created; Stripe call not yet made or in-flight.
     */
    INITIALIZING,

    /**
     * Stripe Checkout Session is active; waiting for customer or webhook.
     */
    OPEN,

    /**
     * Stripe confirmed payment success (provider truth).
     * This attempt may or may not be the canonical succeededAttemptId on Payment.
     */
    SUCCEEDED,

    /**
     * Stripe confirmed payment failure (declined, cancelled by customer, etc.).
     */
    FAILED,

    /**
     * Stripe Checkout Session expired (confirmed by Stripe event or reconciliation).
     * NOT to be used for local network timeouts — use UNKNOWN for those.
     */
    EXPIRED,

    /**
     * We initiated the attempt but never received a confirmed provider outcome.
     * Caused by network timeout, lost response, or service crash.
     * Must be resolved via Stripe API lookup before treating as terminal.
     * See Failure Matrix D12.
     */
    UNKNOWN
}