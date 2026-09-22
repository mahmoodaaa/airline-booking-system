package com.project.paymentservice.controller;

import com.project.paymentservice.service.StripeWebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
public class StripeWebhookController {

    private final StripeWebhookService stripeWebhookService;

    @PostMapping("/stripe")
    public ResponseEntity<Void> handleStripeWebhook(@RequestBody byte[] rawBody,
            @RequestHeader(value = "Stripe-Signature", required = false) String signatureHeader) {

        stripeWebhookService.processWebhook(rawBody, signatureHeader);

        return ResponseEntity.ok().build();
    }
}