package com.project.flightservice.dto.response;

import com.project.flightservice.enums.AirportStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AirportResponse {
    private UUID id;
    private String iataCode;
    private String icaoCode;
    private String name;
    private String city;
    private String country;
    private String timezone;
    private AirportStatus status;
    private LocalDateTime createdAt;
}
