package com.project.paymentservice.service.model;

import java.util.UUID;

public record PaymentSuccessResult(

        UUID paymentId,

        UUID bookingId,

        UUID attemptId,

        boolean canonicalSuccess,

        boolean bookingConfirmationEligible

) {
}