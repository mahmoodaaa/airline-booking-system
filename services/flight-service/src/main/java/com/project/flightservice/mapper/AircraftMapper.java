package com.project.flightservice.mapper;

import com.project.flightservice.dto.request.AircraftRequest;
import com.project.flightservice.dto.response.AircraftResponse;
import com.project.flightservice.entity.Aircraft;
import com.project.flightservice.enums.AircraftStatus;
import org.springframework.stereotype.Component;

@Component
public class AircraftMapper {

    public Aircraft toEntity(AircraftRequest request) {
        if (request == null) return null;

        return Aircraft.builder()
                .registrationNumber(request.getRegistrationNumber().toUpperCase())
                .manufacturer(request.getManufacturer())
                .model(request.getModel())
                .totalCapacity(request.getTotalCapacity())
                .status(AircraftStatus.ACTIVE)
                .build();
    }

    public AircraftResponse toResponse(Aircraft aircraft) {
        if (aircraft == null) return null;

        return AircraftResponse.builder()
                .id(aircraft.getId())
                .registrationNumber(aircraft.getRegistrationNumber())
                .manufacturer(aircraft.getManufacturer())
                .model(aircraft.getModel())
                .totalCapacity(aircraft.getTotalCapacity())
                .status(aircraft.getStatus())
                .createdAt(aircraft.getCreatedAt())
                .build();
    }
}
