package com.project.paymentservice.service.model;


import com.project.paymentservice.entity.PaymentAttempt;

public record AttemptClaimResult(
        PaymentAttempt attempt,
        boolean createdNewAttempt
) {
}