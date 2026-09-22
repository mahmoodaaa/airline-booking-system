package com.project.paymentservice.dto.response;

import com.project.paymentservice.enums.PaymentAttemptStatus;
import com.project.paymentservice.enums.PaymentMethodType;
import com.project.paymentservice.enums.PaymentProvider;
import com.project.paymentservice.enums.PaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentInitiationResponse {

    private UUID paymentId;
    private UUID attemptId;

    private PaymentProvider provider;
    private PaymentMethodType paymentMethod;

    private PaymentStatus paymentStatus;
    private PaymentAttemptStatus attemptStatus;

    /**
     * Provider-hosted URL where the customer should be redirected
     * to continue the payment flow.
     *
     * May be null when no customer redirect is currently available
     * or required.
     */
    private String redirectUrl;

    /**
     * Expiration time of the current provider payment/checkout flow,
     * when the provider exposes one.
     */
    private LocalDateTime expiresAt;
}