package com.project.flightservice.dto.response;

import com.project.flightservice.enums.AircraftStatus;
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
public class AircraftResponse {
    private UUID id;
    private String registrationNumber;
    private String manufacturer;
    private String model;
    private Integer totalCapacity;
    private AircraftStatus status;
    private LocalDateTime createdAt;
}
