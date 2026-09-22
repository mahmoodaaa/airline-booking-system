package com.project.bookingservice.controller;

import com.project.common.response.ApiResponse;
import com.project.bookingservice.dto.response.PaymentContextResponse;
import com.project.bookingservice.service.BookingService;
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

    @GetMapping("/{bookingId}/payment-context")
    public ResponseEntity<ApiResponse<PaymentContextResponse>> getPaymentContext(
            @PathVariable UUID bookingId) {
        
        PaymentContextResponse response = bookingService.getPaymentContext(bookingId);
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
