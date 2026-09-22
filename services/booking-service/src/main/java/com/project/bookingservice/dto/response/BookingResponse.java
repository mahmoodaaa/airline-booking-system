package com.project.bookingservice.dto.response;

import com.project.bookingservice.enums.BookingStatus;
import com.project.bookingservice.enums.Currency;
import com.project.bookingservice.enums.FareClassType;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingResponse {

    private UUID id;
    private UUID userId;
    private UUID flightId;
    private UUID fareClassId;

    private String flightNumber;
    private String originIata;
    private String destinationIata;
    private LocalDateTime departureTime;
    private LocalDateTime arrivalTime;
    private FareClassType fareClassType;

    private BigDecimal priceAtBooking;
    private BigDecimal totalAmount;
    private Currency currency;

    private UUID paymentId;
    private LocalDateTime confirmedAt;

    private BookingStatus status;
    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private List<PassengerResponse> passengers;
}
