package com.project.paymentservice.controller;

import com.project.common.response.ApiResponse;
import com.project.paymentservice.dto.request.PaymentInitiationRequest;
import com.project.paymentservice.dto.response.PaymentInitiationResponse;
import com.project.paymentservice.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;


    @PostMapping
    public ResponseEntity<ApiResponse<PaymentInitiationResponse>> initiatePayment(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PaymentInitiationRequest request) {

        UUID userId = UUID.fromString(authentication.getName());

        PaymentInitiationResponse response = paymentService.initiatePayment(userId, idempotencyKey, request);

        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.success("Payment checkout initiated successfully", response));
    }
}