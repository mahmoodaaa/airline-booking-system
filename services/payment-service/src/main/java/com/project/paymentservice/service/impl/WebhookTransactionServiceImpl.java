package com.project.paymentservice.service.impl;

import com.project.paymentservice.entity.StripeWebhookEvent;
import com.project.paymentservice.enums.WebhookProcessingStatus;
import com.project.paymentservice.repository.StripeWebhookEventRepository;
import com.project.paymentservice.service.WebhookTransactionService;
import com.project.paymentservice.enums.WebhookProcessingDecision;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookTransactionServiceImpl implements WebhookTransactionService {

    private static final int MAX_ERROR_LENGTH = 500;

    private final StripeWebhookEventRepository webhookEventRepository;


    // ============================================================
    // REGISTER
    // ============================================================

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StripeWebhookEvent registerEvent(String stripeEventId, String eventType, String payloadHash) {

        validateIdentity(stripeEventId, eventType);

        LocalDateTime now = LocalDateTime.now();


        int inserted = webhookEventRepository.insertIfAbsent(
                        UUID.randomUUID(),
                        stripeEventId,
                        eventType,
                        WebhookProcessingStatus.RECEIVED
                                .name(), payloadHash, now);


        StripeWebhookEvent event = webhookEventRepository.findByStripeEventId(stripeEventId)
                        .orElseThrow(() -> new IllegalStateException("Webhook event missing after registration"));


        // ========================================================
        // First delivery
        // ========================================================

        if (inserted == 1) {
            log.info("Registered Stripe webhook event. eventId={} type={}", stripeEventId, eventType);
            return event;
        }


        // ========================================================
        // Duplicate delivery
        //
        // Same Stripe event ID should represent the same event.
        // ========================================================

        if (!eventType.equals(event.getEventType())) {

            log.error(
                    "CRITICAL: Stripe event ID reused with different event type. " +
                            "eventId={} storedType={} incomingType={}",
                    stripeEventId,
                    event.getEventType(),
                    eventType
            );

            throw new IllegalStateException("Stripe webhook event identity mismatch"
            );
        }


        /*
         * payloadHash is useful as an audit/integrity guard.
         *
         * If both values exist and differ, do not silently
         * treat the payload as an ordinary duplicate.
         */
        if (event.getPayloadHash() != null && payloadHash != null && !event.getPayloadHash().equals(payloadHash)) {

            log.error("CRITICAL: Duplicate Stripe event has different payload hash. " + "eventId={}", stripeEventId);

            throw new IllegalStateException(
                    "Stripe webhook payload mismatch"
            );
        }


        log.debug("Stripe webhook event already registered. " + "eventId={} status={}", stripeEventId, event.getProcessingStatus());


        return event;
    }


    // ============================================================
    // CLAIM
    // ============================================================

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WebhookProcessingDecision claimForProcessing(String stripeEventId) {

        LocalDateTime now = LocalDateTime.now();


        int updated = webhookEventRepository.claimForProcessing(
                        stripeEventId,
                        WebhookProcessingStatus.RECEIVED,
                        WebhookProcessingStatus.FAILED,
                        WebhookProcessingStatus.PROCESSING,
                        now
                );


        /*
         * This worker changed:
         *
         * RECEIVED/FAILED -> PROCESSING
         */
        if (updated == 1) {

            log.debug("Claimed Stripe webhook for processing. eventId={}", stripeEventId);

            return WebhookProcessingDecision.PROCESS;
        }


        StripeWebhookEvent event = webhookEventRepository.findByStripeEventId(stripeEventId)
                                 .orElseThrow(() -> new IllegalStateException("Webhook event missing during processing claim"));


        return switch (event.getProcessingStatus()) {

            case PROCESSED -> {

                log.debug("Stripe webhook already processed. eventId={}", stripeEventId);

                yield WebhookProcessingDecision.ALREADY_PROCESSED;
            }


            case PROCESSING -> {
                log.debug("Stripe webhook already being processed. eventId={}", stripeEventId);

                yield WebhookProcessingDecision.IN_PROGRESS;
            }


            /*
             * If we're seeing RECEIVED/FAILED here after our CAS
             * returned zero, another transaction may have changed
             * state around us.
             *
             * Do not guess or execute financial processing twice.
             */
            case RECEIVED, FAILED ->
                    throw new IllegalStateException(
                            "Webhook processing claim could not be resolved safely"
                    );
        };
    }


    // ============================================================
    // SUCCESS
    // ============================================================

    @Override
    @Transactional(
            propagation = Propagation.REQUIRES_NEW
    )
    public void markProcessed(String stripeEventId) {

        int updated = webhookEventRepository.markProcessed(
                        stripeEventId,
                        WebhookProcessingStatus.PROCESSING,
                        WebhookProcessingStatus.PROCESSED,
                        LocalDateTime.now()
                );


        if (updated == 1) {
            return;
        }


        StripeWebhookEvent event = webhookEventRepository.findByStripeEventId(stripeEventId)
                .orElseThrow(() -> new IllegalStateException("Webhook event missing while marking processed"));


        /*
         * Idempotent replay of local finalization.
         */
        if (event.getProcessingStatus() == WebhookProcessingStatus.PROCESSED) {
            return;
        }

        throw new IllegalStateException("Webhook event could not transition to PROCESSED");
    }


    // ============================================================
    // FAILURE
    // ============================================================

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String stripeEventId, String error) {

        String safeError = sanitizeError(error);

        int updated = webhookEventRepository.markFailed(
                        stripeEventId,
                        WebhookProcessingStatus.PROCESSING,
                        WebhookProcessingStatus.FAILED,
                        safeError,
                        LocalDateTime.now()
                );


        if (updated == 1) {

            log.warn(
                    "Stripe webhook processing marked FAILED. eventId={}",
                    stripeEventId
            );

            return;
        }


        StripeWebhookEvent event = webhookEventRepository.findByStripeEventId(stripeEventId)
                   .orElseThrow(() -> new IllegalStateException("Webhook event missing while marking failed"));


        if (event.getProcessingStatus() == WebhookProcessingStatus.FAILED) {
            return;
        }


        /*
         * Do not overwrite PROCESSED with FAILED.
         */
        if (event.getProcessingStatus() == WebhookProcessingStatus.PROCESSED) {

            log.warn("Ignoring FAILED transition because webhook is already PROCESSED. eventId={}", stripeEventId);

            return;
        }

        throw new IllegalStateException("Webhook event could not transition to FAILED");
    }


    // ============================================================
    // Helpers
    // ============================================================

    private void validateIdentity(String stripeEventId, String eventType) {

        if (stripeEventId == null || stripeEventId.isBlank()) {
            throw new IllegalArgumentException("Stripe event ID is required");
        }

        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("Stripe event type is required");
        }
    }


    private String sanitizeError(String error) {

        if (error == null || error.isBlank()) {
            return "Webhook processing failed";
        }

        if (error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }

        return error.substring(0, MAX_ERROR_LENGTH);
    }
}