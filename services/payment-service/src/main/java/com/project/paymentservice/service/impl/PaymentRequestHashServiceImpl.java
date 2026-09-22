package com.project.paymentservice.service.impl;

import com.project.paymentservice.enums.PaymentMethodType;
import com.project.paymentservice.enums.PaymentProvider;
import com.project.paymentservice.service.PaymentRequestHashService;
import org.springframework.stereotype.Service;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

@Service

public class PaymentRequestHashServiceImpl implements PaymentRequestHashService {

    @Override
    public String generateHash(UUID bookingId, PaymentProvider provider, PaymentMethodType paymentMethod) {

        Objects.requireNonNull(bookingId, "bookingId must not be null");

        Objects.requireNonNull(provider, "provider must not be null");

        Objects.requireNonNull(paymentMethod, "paymentMethod must not be null");

        String normalized = String.format("v1|bookingId=%s|provider=%s|paymentMethod=%s", bookingId, provider.name(), paymentMethod.name());

        return sha256(normalized);
    }

    @Override
    public String hashIdempotencyKey(String idempotencyKey) {

        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        return sha256(idempotencyKey);
    }

    private String sha256(String value) {
        try {

            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));

        } catch (NoSuchAlgorithmException e) {

            throw new IllegalStateException("SHA-256 algorithm is unavailable", e);
        }
    }
}
