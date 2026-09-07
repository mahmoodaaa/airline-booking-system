package com.project.flightservice.service;

import com.project.flightservice.dto.request.AircraftRequest;
import com.project.flightservice.dto.request.UpdateAircraftStatusRequest;
import com.project.flightservice.dto.response.AircraftResponse;
import java.util.List;
import java.util.UUID;

public interface AircraftService {
    AircraftResponse createAircraft(AircraftRequest request);
    AircraftResponse getAircraftById(UUID id);
    List<AircraftResponse> getAllAircraft();
    AircraftResponse updateAircraft(UUID id, AircraftRequest request);
    AircraftResponse updateStatus(UUID id, UpdateAircraftStatusRequest request);
}
