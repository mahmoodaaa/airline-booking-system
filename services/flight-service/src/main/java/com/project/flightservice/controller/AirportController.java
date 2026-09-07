package com.project.flightservice.controller;

import com.project.common.response.ApiResponse;
import com.project.flightservice.dto.request.AirportRequest;
import com.project.flightservice.dto.request.UpdateAirportStatusRequest;
import com.project.flightservice.dto.response.AirportResponse;
import com.project.flightservice.service.AirportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/airports")
@RequiredArgsConstructor
@Tag(name = "Airport Management", description = "Admin APIs for managing airports")
public class AirportController {

    private final AirportService airportService;

    @Operation(
            summary = "Create a new airport",
            description = "🔑 **Access Level:** ADMIN only\n\nAdds a new airport to the system."
    )
    @PostMapping
    public ResponseEntity<ApiResponse<AirportResponse>> createAirport(@Valid @RequestBody AirportRequest request) {
        AirportResponse response = airportService.createAirport(request);
        return new ResponseEntity<>(ApiResponse.success("Airport created successfully", response), HttpStatus.CREATED);
    }

    @Operation(
            summary = "Get all airports",
            description = "🔑 **Access Level:** ADMIN only\n\nRetrieves a list of all managed airports."
    )
    @GetMapping
    public ResponseEntity<ApiResponse<List<AirportResponse>>> getAllAirports() {
        List<AirportResponse> response = airportService.getAllAirports();
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
            summary = "Get airport by ID",
            description = "🔑 **Access Level:** ADMIN only\n\nRetrieves airport details by its UUID."
    )
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AirportResponse>> getAirportById(@PathVariable UUID id) {
        AirportResponse response = airportService.getAirportById(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
            summary = "Get airport by IATA code",
            description = "🔑 **Access Level:** ADMIN only\n\nRetrieves airport details using its 3-letter IATA code."
    )
    @GetMapping("/iata/{code}")
    public ResponseEntity<ApiResponse<AirportResponse>> getAirportByIata(@PathVariable String code) {
        AirportResponse response = airportService.getAirportByIata(code);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
            summary = "Update airport details",
            description = "🔑 **Access Level:** ADMIN only\n\nUpdates general details of an existing airport."
    )
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<AirportResponse>> updateAirport(@PathVariable UUID id, @Valid @RequestBody AirportRequest request) {
        AirportResponse response = airportService.updateAirport(id, request);
        return ResponseEntity.ok(ApiResponse.success("Airport updated successfully", response));
    }

    @Operation(
            summary = "Update airport status",
            description = "🔑 **Access Level:** ADMIN only\n\nUpdates the operational status of an airport (e.g., ACTIVE, CLOSED)."
    )
    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<AirportResponse>> updateStatus(@PathVariable UUID id, @Valid @RequestBody UpdateAirportStatusRequest request) {
        AirportResponse response = airportService.updateStatus(id, request);
        return ResponseEntity.ok(ApiResponse.success("Airport status updated successfully", response));
    }

    @Operation(
            summary = "Delete (Deactivate) an airport",
            description = "🔑 **Access Level:** ADMIN only\n\nSoft deletes an airport by changing its status."
    )
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteAirport(@PathVariable UUID id) {
        airportService.deleteAirport(id);
        return ResponseEntity.ok(ApiResponse.success("Airport deactivated successfully", null));
    }
}
