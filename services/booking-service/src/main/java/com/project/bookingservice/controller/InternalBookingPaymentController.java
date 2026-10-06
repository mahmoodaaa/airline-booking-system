package com.project.bookingservice.controller;

import com.project.common.response.ApiResponse;
import com.project.bookingservice.dto.request.StartPaymentRequest;
import com.project.bookingservice.dto.response.PaymentContextResponse;
import com.project.bookingservice.service.BookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.UUID;

@RestController
@RequestMapping("/internal/bookings")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SERVICE') and authentication.name == 'payment-service'")
public class InternalBookingPaymentController {

    private final BookingService bookingService;

    /**
     * Atomically transitions:
     *
     * PENDING -> PAYMENT_PENDING
     *
     * and replaces expiresAt once with the protected payment-window deadline.
     *
     * Returns the authoritative payment context:
     * amount, currency, status and effective expiresAt.
     *
     * Idempotent:
     * if already PAYMENT_PENDING and expiresAt is still valid,
     * returns the existing context without extending expiresAt.
     */
    @PostMapping("/{bookingId}/start-payment")
    public ResponseEntity<ApiResponse<PaymentContextResponse>> startPayment(
            @PathVariable UUID bookingId,
            @Valid @RequestBody StartPaymentRequest request) {

        PaymentContextResponse response = bookingService.startPayment(bookingId, request.userId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/{bookingId}/payments/{paymentId}/confirm")
    public ResponseEntity<ApiResponse<Void>> confirmPayment(
            @PathVariable UUID bookingId,
            @PathVariable UUID paymentId) {

        bookingService.confirmPayment(bookingId, paymentId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}

