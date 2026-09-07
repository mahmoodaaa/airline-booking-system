package com.project.flightservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AircraftRequest {

    @NotBlank
    private String registrationNumber;

    @NotBlank
    private String manufacturer;

    @NotBlank
    private String model;

    @Positive
    private Integer totalCapacity;
}
