package com.project.bookingservice.dto.response;

import com.project.bookingservice.enums.BookingStatus;
import com.project.bookingservice.enums.Currency;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Authoritative payment context returned by:
 * POST /internal/bookings/{id}/start-payment
 *
 * Contains everything Payment Service needs:
 * - totalAmount / currency -> authoritative payable snapshot
 * - status                 -> PAYMENT_PENDING expected
 * - expiresAt              -> current effective Booking reservation deadline
 *
 * Payment Service must not extend or choose this deadline.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentContextResponse {

    private UUID bookingId;
    private UUID userId;

    private BookingStatus status;

    private BigDecimal totalAmount;
    private Currency currency;

    /**
     * Current effective reservation deadline.
     *
     * While PAYMENT_PENDING this is the upper Booking-side boundary
     * for completing payment and Booking confirmation.
     */
    private LocalDateTime expiresAt;
}