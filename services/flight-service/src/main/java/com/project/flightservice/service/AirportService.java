package com.project.flightservice.service;

import com.project.flightservice.dto.request.AirportRequest;
import com.project.flightservice.dto.request.UpdateAirportStatusRequest;
import com.project.flightservice.dto.response.AirportResponse;
import java.util.List;
import java.util.UUID;

public interface AirportService {
    AirportResponse createAirport(AirportRequest request);
    AirportResponse getAirportById(UUID id);
    AirportResponse getAirportByIata(String iataCode);
    List<AirportResponse> getAllAirports();
    AirportResponse updateAirport(UUID id, AirportRequest request);
    AirportResponse updateStatus(UUID id, UpdateAirportStatusRequest request);
    void deleteAirport(UUID id);
}
