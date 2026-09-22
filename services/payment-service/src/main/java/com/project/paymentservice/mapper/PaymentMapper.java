package com.project.paymentservice.mapper;

import com.project.paymentservice.dto.response.PaymentInitiationResponse;
import com.project.paymentservice.entity.Payment;
import com.project.paymentservice.entity.PaymentAttempt;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class PaymentMapper {

    public PaymentInitiationResponse toInitiationResponse(
            Payment payment,
            PaymentAttempt attempt
    ) {

        Objects.requireNonNull(payment, "Payment must not be null");

        PaymentInitiationResponse.PaymentInitiationResponseBuilder builder =
                PaymentInitiationResponse.builder()
                        .paymentId(payment.getId())
                        .paymentStatus(payment.getStatus());

        if (attempt != null) {
            builder.attemptId(attempt.getId())
                    .provider(attempt.getProvider())
                    .paymentMethod(attempt.getPaymentMethod())
                    .attemptStatus(attempt.getStatus())
                    .redirectUrl(attempt.getRedirectUrl())
                    .expiresAt(attempt.getProviderExpiresAt());
        }
        return builder.build();
    }
}