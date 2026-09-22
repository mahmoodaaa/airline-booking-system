package com.project.paymentservice.service;

public interface StripeWebhookService {

    void processWebhook(byte[] rawBody, String signatureHeader);
}