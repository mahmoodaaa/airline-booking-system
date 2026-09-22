package com.project.paymentservice.repository;

import com.project.paymentservice.entity.StripeWebhookEvent;
import com.project.paymentservice.enums.WebhookProcessingStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface StripeWebhookEventRepository
        extends JpaRepository<StripeWebhookEvent, UUID> {


    Optional<StripeWebhookEvent> findByStripeEventId(String stripeEventId);


    // ============================================================
    // 1. Idempotent webhook inbox insert
    //
    // PostgreSQL unique constraint on stripe_event_id is the
    // concurrency authority.
    //
    // return:
    // 1 -> inserted
    // 0 -> event already exists
    // ============================================================

    @Modifying
    @Query(
            value = """
                    INSERT INTO stripe_webhook_events (
                        id,
                        stripe_event_id,
                        event_type,
                        processing_status,
                        payload_hash,
                        version,
                        received_at,
                        updated_at
                    )
                    VALUES (
                        :id,
                        :stripeEventId,
                        :eventType,
                        :processingStatus,
                        :payloadHash,
                        0,
                        :now,
                        :now
                    )
                    ON CONFLICT (stripe_event_id)
                    DO NOTHING
                    """,
            nativeQuery = true
    )
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("stripeEventId") String stripeEventId,
                       @Param("eventType") String eventType,
                       @Param("processingStatus") String processingStatus,
                       @Param("payloadHash") String payloadHash,
                       @Param("now") LocalDateTime now);


    // ============================================================
    // 2. Processing claim
    //
    // Only RECEIVED or FAILED may become PROCESSING.
    //
    // CAS-style update:
    //
    // RECEIVED ─┐
    //           ├──> PROCESSING
    // FAILED ───┘
    //
    // PROCESSING / PROCESSED cannot be claimed.
    // ============================================================

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE StripeWebhookEvent e
               SET e.processingStatus = :processingStatus,
                   e.updatedAt = :now,
                   e.version = e.version + 1
             WHERE e.stripeEventId = :stripeEventId
               AND (
                    e.processingStatus = :receivedStatus
                    OR e.processingStatus = :failedStatus
               )
            """)
    int claimForProcessing(@Param("stripeEventId") String stripeEventId,
                           @Param("receivedStatus") WebhookProcessingStatus receivedStatus,
                           @Param("failedStatus") WebhookProcessingStatus failedStatus,
                           @Param("processingStatus") WebhookProcessingStatus processingStatus,
                           @Param("now") LocalDateTime now);


    // ============================================================
    // 3. Successful processing
    // ============================================================

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE StripeWebhookEvent e
               SET e.processingStatus = :processedStatus,
                   e.processedAt = :now,
                   e.updatedAt = :now,
                   e.lastError = null,
                   e.version = e.version + 1
             WHERE e.stripeEventId = :stripeEventId
               AND e.processingStatus = :processingStatus
            """)
    int markProcessed(@Param("stripeEventId") String stripeEventId,
                      @Param("processingStatus") WebhookProcessingStatus processingStatus,
                      @Param("processedStatus") WebhookProcessingStatus processedStatus,
                      @Param("now") LocalDateTime now);


    // ============================================================
    // 4. Failed processing
    // ============================================================

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE StripeWebhookEvent e
               SET e.processingStatus = :failedStatus,
                   e.lastError = :lastError,
                   e.updatedAt = :now,
                   e.version = e.version + 1
             WHERE e.stripeEventId = :stripeEventId
               AND e.processingStatus = :processingStatus
            """)
    int markFailed(@Param("stripeEventId") String stripeEventId,
                   @Param("processingStatus") WebhookProcessingStatus processingStatus,
                   @Param("failedStatus") WebhookProcessingStatus failedStatus,
                   @Param("lastError") String lastError,
                   @Param("now") LocalDateTime now);
}