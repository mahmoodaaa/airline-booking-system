package com.project.paymentservice.dto.request;

import com.project.paymentservice.enums.PaymentMethodType;
import com.project.paymentservice.enums.PaymentProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class PaymentInitiationRequest {

    @NotNull(message = "Booking ID is required")
    private UUID bookingId;

    @NotNull(message = "Payment provider is required")
    private PaymentProvider provider;

    @NotNull(message = "Payment method is required")
    private PaymentMethodType paymentMethod;
}