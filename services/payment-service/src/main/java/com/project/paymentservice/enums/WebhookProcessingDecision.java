package com.project.paymentservice.enums;

public enum WebhookProcessingDecision {

    /*
     * This worker successfully claimed the event
     * and is allowed to execute business processing.
     */
    PROCESS,

    /*
     * Event already completed successfully.
     *
     * Duplicate Stripe delivery -> safely return 200.
     */
    ALREADY_PROCESSED,

    /*
     * Another worker currently owns this event.
     *
     * Do NOT process concurrently.
     */
    IN_PROGRESS
}