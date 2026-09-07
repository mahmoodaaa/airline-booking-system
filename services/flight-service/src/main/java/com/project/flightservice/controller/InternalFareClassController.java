package com.project.flightservice.controller;

import com.project.common.response.ApiResponse;
import com.project.flightservice.dto.request.SeatOperationRequest;
import com.project.flightservice.service.FlightService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import com.project.flightservice.dto.response.SeatReservationResponse;

@Hidden
@RestController
@RequestMapping("/internal/flights/{flightId}/fare-classes")
@RequiredArgsConstructor
public class InternalFareClassController {

    private final FlightService flightService;

    @PostMapping("/{fareClassId}/reserve")
    public ResponseEntity<ApiResponse<SeatReservationResponse>> reserveSeats(
            @PathVariable UUID flightId,
            @PathVariable UUID fareClassId,
            @Valid @RequestBody SeatOperationRequest request) {
        
        SeatReservationResponse response = flightService.reserveSeats(flightId, fareClassId, request.getCount());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/{fareClassId}/release")
    public ResponseEntity<ApiResponse<Void>> releaseSeats(
            @PathVariable UUID flightId,
            @PathVariable UUID fareClassId,
            @Valid @RequestBody SeatOperationRequest request) {
        
        flightService.releaseSeats(flightId, fareClassId, request.getCount());
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
