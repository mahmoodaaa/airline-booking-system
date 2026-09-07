package com.project.flightservice.controller;

import com.project.common.response.ApiResponse;
import com.project.flightservice.dto.request.FlightRequest;
import com.project.flightservice.dto.request.UpdateFlightStatusRequest;
import com.project.flightservice.dto.response.AvailabilityResponse;
import com.project.flightservice.dto.response.FlightResponse;
import com.project.flightservice.dto.response.FlightSearchResponse;
import com.project.flightservice.service.FlightService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;

@RestController
@RequestMapping("/api/flights")
@RequiredArgsConstructor
@Tag(name = "Flight Management", description = "APIs for searching, viewing, and managing flights")
public class FlightController {

    private final FlightService flightService;

    @Operation(
            summary = "Create a new flight",
            description = "🔑 **Access Level:** ADMIN only\n\nSchedules a new flight and generates fare classes based on aircraft capacity."
    )
    @PostMapping
    public ResponseEntity<ApiResponse<FlightResponse>> createFlight(@Valid @RequestBody FlightRequest request) {
        FlightResponse response = flightService.createFlight(request);
        return new ResponseEntity<>(ApiResponse.success("Flight created successfully", response), HttpStatus.CREATED);
    }

    @Operation(
            summary = "List all flights (Admin Dashboard)",
            description = "🔑 **Access Level:** ADMIN only\n\nRetrieves all flights (including cancelled and completed) with pagination. Use `?page=0&size=20&sort=departureTime,asc`."
    )
    @GetMapping
    public ResponseEntity<ApiResponse<Page<FlightResponse>>> getAllFlights(
            @ParameterObject @PageableDefault(size = 20, sort = "departureTime", direction = Sort.Direction.ASC) Pageable pageable) {
        Page<FlightResponse> response = flightService.getAllFlights(pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
            summary = "Get flight details",
            description = "🔑 **Access Level:** Public\n\nRetrieves comprehensive details of a specific flight by its UUID."
    )
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<FlightResponse>> getFlightById(@PathVariable UUID id) {
        FlightResponse response = flightService.getFlightById(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
            summary = "Search flights",
            description = "🔑 **Access Level:** Public\n\nSearches for available flights between two airports on a specific date."
    )
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<FlightSearchResponse>>> searchFlights(
            @io.swagger.v3.oas.annotations.Parameter(description = "3-letter IATA code of the origin airport (e.g., AMM)") @RequestParam String origin,
            @io.swagger.v3.oas.annotations.Parameter(description = "3-letter IATA code of the destination airport (e.g., DXB)") @RequestParam String destination,
            @io.swagger.v3.oas.annotations.Parameter(description = "Date of the flight (YYYY-MM-DD)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        List<FlightSearchResponse> response = flightService.searchFlights(origin, destination, date);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
            summary = "Get flight seat availability",
            description = "🔑 **Access Level:** Public\n\nRetrieves real-time seat availability across all fare classes for a specific flight."
    )
    @GetMapping("/{id}/availability")
    public ResponseEntity<ApiResponse<AvailabilityResponse>> getAvailability(@PathVariable UUID id) {
        AvailabilityResponse response = flightService.getAvailability(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
            summary = "Update flight status",
            description = "🔑 **Access Level:** ADMIN only\n\nUpdates the status of a flight (e.g., SCHEDULED, DELAYED, CANCELLED, COMPLETED)."
    )
    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<FlightResponse>> updateStatus(
            @PathVariable UUID id, @Valid @RequestBody UpdateFlightStatusRequest request) {
        FlightResponse response = flightService.updateFlightStatus(id, request);
        return ResponseEntity.ok(ApiResponse.success("Flight status updated successfully", response));
    }
}
