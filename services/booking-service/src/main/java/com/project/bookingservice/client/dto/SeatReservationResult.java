package com.project.bookingservice.client.dto;

import com.project.bookingservice.enums.Currency;
import com.project.bookingservice.enums.FareClassType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Local mirror of flight-service SeatReservationResponse.
 * Must stay in sync with flight-service contract.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeatReservationResult {

    private UUID flightId;
    private UUID fareClassId;

    private String flightNumber;

    private String originIata;
    private String destinationIata;

    private LocalDateTime departureTime;
    private LocalDateTime arrivalTime;

    private FareClassType fareClassType;

    private BigDecimal price;
    private Currency currency;

    private int availableSeats;
}
