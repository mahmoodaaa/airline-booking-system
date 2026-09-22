package com.project.paymentservice.client.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO returned by booking-service internal endpoint:
 * GET /internal/bookings/{bookingId}/payment-context
 *
 * Contains the minimum data payment-service needs to validate
 * and initiate a charge. All business guard checks are performed
 * by payment-service after receiving this context:
 *
 *   status     == "PENDING"?
 *   userId     == authenticated customer?
 *   expiresAt  > now?
 *   totalAmount valid (> 0)?
 *   currency   supported?
 *
 * status is kept as String intentionally — avoids sharing BookingStatus
 * business enum across service boundaries.
 */
@Getter
@Setter
@NoArgsConstructor
public class BookingPaymentContext {

    private UUID bookingId;
    private UUID userId;

    private BigDecimal totalAmount;
    private String currency;        // ISO-4217, e.g. "USD"

    private String status;          // "PENDING" expected; anything else is rejected

    /**
     * Booking reservation expiry time (UTC).
     * Payment-service checks expiresAt > now before initiating charge.
     * A booking that expires mid-flow is handled via CAS + technical refund.
     */
    private LocalDateTime expiresAt;
}
