package com.project.flightservice.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlightRequest {

    @NotBlank
    private String flightNumber;

    @NotNull
    private UUID originAirportId;

    @NotNull
    private UUID destinationAirportId;

    @NotNull
    private UUID aircraftId;

    @NotNull
    private LocalDateTime departureTime;

    @NotNull
    private LocalDateTime arrivalTime;

    @NotEmpty
    @Valid
    private List<FareClassRequest> fares;

    @jakarta.validation.constraints.AssertTrue(message = "Origin and destination airports must be different")
    public boolean isOriginDifferentFromDestination() {
        return originAirportId == null
                || destinationAirportId == null
                || !originAirportId.equals(destinationAirportId);
    }

    @jakarta.validation.constraints.AssertTrue(message = "Arrival time must be after departure time")
    public boolean isArrivalAfterDeparture() {
        return departureTime == null
                || arrivalTime == null
                || arrivalTime.isAfter(departureTime);
    }
}
