package com.project.paymentservice.service;

import com.project.paymentservice.entity.StripeWebhookEvent;
import com.project.paymentservice.enums.WebhookProcessingDecision;

public interface WebhookTransactionService {

    /**
     * Persist the verified Stripe event if this is its first delivery.
     *
     * Uses:
     *
     * INSERT ... ON CONFLICT DO NOTHING
     *
     * Must commit independently before processing begins.
     */
    StripeWebhookEvent registerEvent(String stripeEventId, String eventType, String payloadHash);

    /**
     * Atomically claims an event for processing.
     *
     * RECEIVED / FAILED -> PROCESSING
     */
    WebhookProcessingDecision claimForProcessing(String stripeEventId);

    /**
     * PROCESSING -> PROCESSED
     */
    void markProcessed(String stripeEventId);

    /**
     * PROCESSING -> FAILED
     */
    void markFailed(String stripeEventId, String error);
}