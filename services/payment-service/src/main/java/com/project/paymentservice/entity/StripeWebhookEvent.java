package com.project.paymentservice.entity;

import com.project.paymentservice.enums.WebhookProcessingStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "stripe_webhook_events",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_stripe_webhook_event_id",
                        columnNames = "stripe_event_id"
                )
        },
        indexes = {
                @Index(
                        name = "idx_webhook_status",
                        columnList = "processing_status"
                ),
                @Index(
                        name = "idx_webhook_session_id",
                        columnList = "stripe_checkout_session_id"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StripeWebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // =========================================
    // Stripe event identity
    // =========================================

    @Column(name = "stripe_event_id", nullable = false, length = 255, updatable = false)
    private String stripeEventId;

    @Column(name = "event_type", nullable = false, length = 150, updatable = false)
    private String eventType;

    // =========================================
    // Provider references
    // =========================================

    @Column(name = "stripe_checkout_session_id", length = 255)
    private String stripeCheckoutSessionId;

    @Column(name = "stripe_payment_intent_id", length = 255)
    private String stripePaymentIntentId;

    // =========================================
    // Local correlation
    // =========================================

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "attempt_id")
    private UUID attemptId;

    // =========================================
    // Processing lifecycle
    // =========================================

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 30)
    private WebhookProcessingStatus processingStatus;

    @Column(name = "payload_hash", length = 64)
    private String payloadHash;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    // =========================================
    // Audit
    // =========================================

    @CreationTimestamp
    @Column(name = "received_at", nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;
}