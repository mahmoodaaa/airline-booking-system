package com.project.flightservice.dto.response;

import com.project.flightservice.enums.FlightStatus;
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
public class FlightResponse {
    private UUID id;
    private String flightNumber;
    private AirportResponse origin;
    private AirportResponse destination;
    private AircraftResponse aircraft;
    private LocalDateTime departureTime;
    private LocalDateTime arrivalTime;
    private FlightStatus status;
    private List<FareClassResponse> fares;
    private LocalDateTime createdAt;
}
