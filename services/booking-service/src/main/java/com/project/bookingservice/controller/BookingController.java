package com.project.bookingservice.controller;

import com.project.bookingservice.dto.request.BookingRequest;
import com.project.bookingservice.dto.response.BookingResponse;
import com.project.bookingservice.dto.response.CreateBookingResult;
import com.project.bookingservice.service.BookingService;
import com.project.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
@Tag(name = "Booking", description = "Booking lifecycle APIs — create, view, and cancel flight bookings")
@Validated
public class BookingController {

    private final BookingService bookingService;

    // ─────────────────────────────────────────────────────
    // CUSTOMER — Create
    // ─────────────────────────────────────────────────────

    @Operation(
            summary = "Create a new booking",
            description = "🔑 **Access Level:** CUSTOMER\n\n" +
                    "Reserves seats and creates a PENDING booking with a configurable TTL.\n\n" +
                    "**Idempotent:** Supply the same `Idempotency-Key` header with the same request " +
                    "body to safely retry without duplicate bookings.\n\n" +
                    "Responds `409 Conflict` if the same key is reused with a different request payload."
    )
    @PostMapping
    @PreAuthorize("hasAuthority('CUSTOMER')")
    public ResponseEntity<ApiResponse<BookingResponse>> createBooking(Authentication authentication,
            @RequestHeader("Idempotency-Key")
            @NotBlank(message = "Idempotency-Key is required")
            @Size(max = 100, message = "Idempotency-Key must not exceed 100 characters")
            String idempotencyKey,
            @Valid @RequestBody BookingRequest request) {

        UUID userId = UUID.fromString(authentication.getName());
        CreateBookingResult result =
                bookingService.createBooking(userId, idempotencyKey.trim(), request);
                
        if (result.created()) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success("Booking created successfully", result.booking()));
        }
        
        return ResponseEntity.ok(
                ApiResponse.success("Existing booking returned for idempotent request", result.booking())
        );
    }

    // ─────────────────────────────────────────────────────
    // CUSTOMER — Read own
    // ─────────────────────────────────────────────────────

    @Operation(
            summary = "Get my booking by ID",
            description = "🔑 **Access Level:** CUSTOMER (owner only)\n\n" +
                    "Returns booking details. Returns `404` if the booking does not belong to the authenticated user."
    )
    @GetMapping("/my/{bookingId}")
    @PreAuthorize("hasAuthority('CUSTOMER')")
    public ResponseEntity<ApiResponse<BookingResponse>> getMyBookingById(
            Authentication authentication,
            @PathVariable UUID bookingId) {

        UUID userId = UUID.fromString(authentication.getName());
        BookingResponse response = bookingService.getMyBookingById(bookingId, userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
            summary = "Get my bookings (paginated)",
            description = "🔑 **Access Level:** CUSTOMER\n\n" +
                    "Returns a paginated list of all bookings belonging to the authenticated user."
    )
    @GetMapping("/my")
    @PreAuthorize("hasAuthority('CUSTOMER')")
    public ResponseEntity<ApiResponse<Page<BookingResponse>>> getMyBookings(
            Authentication authentication,
            @ParameterObject @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {

        UUID userId = UUID.fromString(authentication.getName());
        Page<BookingResponse> response = bookingService.getMyBookings(userId, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ─────────────────────────────────────────────────────
    // CUSTOMER — Cancel
    // ─────────────────────────────────────────────────────

    @Operation(
            summary = "Cancel a booking",
            description = "🔑 **Access Level:** CUSTOMER (owner only)\n\n" +
                    "Atomically transitions the booking:\n" +
                    "`PENDING → CANCELLING → CANCELLED` and releases reserved seats.\n\n" +
                    "Repeating cancellation for an already `CANCELLED` booking is idempotent and returns the existing booking.\n\n" +
                    "Returns `409 Conflict` if the booking cannot be cancelled from its current state (e.g., already expired)."
    )
    @PostMapping("/{bookingId}/cancel")
    @PreAuthorize("hasAuthority('CUSTOMER')")
    public ResponseEntity<ApiResponse<BookingResponse>> cancelBooking(
            Authentication authentication,
            @PathVariable UUID bookingId) {

        UUID userId = UUID.fromString(authentication.getName());
        BookingResponse response = bookingService.cancelBooking(bookingId, userId);
        return ResponseEntity.ok(ApiResponse.success("Booking cancelled successfully", response));
    }

    // ─────────────────────────────────────────────────────
    // ADMIN — Read all
    // ─────────────────────────────────────────────────────

    @Operation(
            summary = "Get booking by ID",
            description = """
                🔑 **Access Level:** ADMIN

                Returns booking details for any booking in the system.

                This endpoint is intended for administrators.
                Customers must use `/api/bookings/my/{bookingId}`.
                """
    )
    @GetMapping("/{bookingId}")
    @PreAuthorize("hasAuthority('ADMIN')")
    public ResponseEntity<ApiResponse<BookingResponse>> getBookingById(
            @PathVariable UUID bookingId) {

        BookingResponse response = bookingService.getBookingById(bookingId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
            summary = "Get all bookings (Admin, paginated)",
            description = "🔑 **Access Level:** ADMIN\n\n" +
                    "Returns a paginated list of all bookings in the system."
    )
    @GetMapping
    @PreAuthorize("hasAuthority('ADMIN')")
    public ResponseEntity<ApiResponse<Page<BookingResponse>>> getAllBookings(
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {

        Page<BookingResponse> response = bookingService.getAllBookings(pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
