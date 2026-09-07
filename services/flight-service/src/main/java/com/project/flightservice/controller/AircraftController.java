package com.project.flightservice.controller;

import com.project.common.response.ApiResponse;
import com.project.flightservice.dto.request.AircraftRequest;
import com.project.flightservice.dto.request.UpdateAircraftStatusRequest;
import com.project.flightservice.dto.response.AircraftResponse;
import com.project.flightservice.service.AircraftService;
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
@RequestMapping("/api/aircraft")
@RequiredArgsConstructor
@Tag(name = "Aircraft Management", description = "Admin APIs for managing fleet and aircrafts")
public class AircraftController {

    private final AircraftService aircraftService;

    @Operation(
            summary = "Create a new aircraft",
            description = "🔑 **Access Level:** ADMIN only\n\nAdds a new aircraft to the fleet."
    )
    @PostMapping
    public ResponseEntity<ApiResponse<AircraftResponse>> createAircraft(@Valid @RequestBody AircraftRequest request) {
        AircraftResponse response = aircraftService.createAircraft(request);
        return new ResponseEntity<>(ApiResponse.success("Aircraft created successfully", response), HttpStatus.CREATED);
    }

    @Operation(
            summary = "Get all aircrafts",
            description = "🔑 **Access Level:** ADMIN only\n\nRetrieves a list of all aircrafts in the fleet."
    )
    @GetMapping
    public ResponseEntity<ApiResponse<List<AircraftResponse>>> getAllAircraft() {
        List<AircraftResponse> response = aircraftService.getAllAircraft();
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
            summary = "Get aircraft details",
            description = "🔑 **Access Level:** ADMIN only\n\nRetrieves specific aircraft details by its UUID."
    )
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AircraftResponse>> getAircraftById(@PathVariable UUID id) {
        AircraftResponse response = aircraftService.getAircraftById(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
            summary = "Update aircraft details",
            description = "🔑 **Access Level:** ADMIN only\n\nUpdates the capacity or details of an existing aircraft."
    )
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<AircraftResponse>> updateAircraft(@PathVariable UUID id, @Valid @RequestBody AircraftRequest request) {
        AircraftResponse response = aircraftService.updateAircraft(id, request);
        return ResponseEntity.ok(ApiResponse.success("Aircraft updated successfully", response));
    }

    @Operation(
            summary = "Update aircraft status",
            description = "🔑 **Access Level:** ADMIN only\n\nUpdates the operational status of an aircraft (e.g., ACTIVE, MAINTENANCE)."
    )
    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<AircraftResponse>> updateStatus(@PathVariable UUID id, @Valid @RequestBody UpdateAircraftStatusRequest request) {
        AircraftResponse response = aircraftService.updateStatus(id, request);
        return ResponseEntity.ok(ApiResponse.success("Aircraft status updated successfully", response));
    }
}
