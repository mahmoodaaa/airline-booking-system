package com.project.bookingservice.dto.response;

import com.project.bookingservice.enums.BookingStatus;
import com.project.bookingservice.enums.Currency;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentContextResponse {
    private UUID bookingId;
    private UUID userId;
    private BookingStatus status;
    private BigDecimal totalAmount;
    private Currency currency;
    private LocalDateTime expiresAt;
}
