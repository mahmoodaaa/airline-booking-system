package com.project.paymentservice.client.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO returned by booking-service internal endpoint:
 * POST /internal/bookings/{bookingId}/start-payment
 *
 * Contains the authoritative context required to build the payment.
 */
@Getter
@Setter
@NoArgsConstructor
public class BookingPaymentContext {

    private UUID bookingId;
    private UUID userId;

    private BigDecimal totalAmount;
    private String currency;

    // PAYMENT_PENDING expected
    private String status;

    /**
     * Current effective Booking reservation deadline.
     *
     * While PAYMENT_PENDING this is the Booking-side
     * deadline by which payment + confirmation must complete.
     */
    private LocalDateTime expiresAt;
}