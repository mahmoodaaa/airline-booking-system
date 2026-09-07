package com.project.flightservice.dto.response;

import com.project.flightservice.enums.Currency;
import com.project.flightservice.enums.FareClassType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeatReservationResponse {

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
