package com.project.bookingservice.service.impl;

import com.project.bookingservice.dto.request.BookingRequest;
import com.project.bookingservice.dto.request.PassengerRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for RequestHashServiceImpl.
 *
 * Verifies that the hash function:
 *  - Produces the same hash for identical requests
 *  - Produces different hashes for different payloads
 *  - Is order-independent (passenger list sorted before hashing)
 *  - Is case/whitespace normalized
 */
@DisplayName("RequestHashServiceImpl Unit Tests")
class RequestHashServiceImplTest {

    RequestHashServiceImpl hashService;

    static final UUID FLIGHT_ID     = UUID.fromString("aaaa0000-0000-0000-0000-000000000000");
    static final UUID FARE_CLASS_ID = UUID.fromString("bbbb0000-0000-0000-0000-000000000000");

    @BeforeEach
    void setup() {
        hashService = new RequestHashServiceImpl();
    }

    // ──────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────

    private PassengerRequest passenger(String first, String last, String passport) {
        return PassengerRequest.builder()
                .firstName(first)
                .lastName(last)
                .passportNumber(passport)
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .nationality("JO")
                .build();
    }

    private BookingRequest request(UUID flightId, UUID fareClassId, List<PassengerRequest> passengers) {
        return BookingRequest.builder()
                .flightId(flightId)
                .fareClassId(fareClassId)
                .passengers(passengers)
                .build();
    }

    // ══════════════════════════════════════════════════════════════
    // Tests
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("identical requests → same hash")
    void identicalRequests_shouldProduceSameHash() {
        BookingRequest r1 = request(FLIGHT_ID, FARE_CLASS_ID,
                List.of(passenger("Ahmad", "Nasser", "PA123")));
        BookingRequest r2 = request(FLIGHT_ID, FARE_CLASS_ID,
                List.of(passenger("Ahmad", "Nasser", "PA123")));

        assertThat(hashService.computeHash(r1)).isEqualTo(hashService.computeHash(r2));
    }

    @Test
    @DisplayName("different flightId → different hash")
    void differentFlightId_shouldProduceDifferentHash() {
        UUID otherFlight = UUID.fromString("cccc0000-0000-0000-0000-000000000000");
        BookingRequest r1 = request(FLIGHT_ID, FARE_CLASS_ID,
                List.of(passenger("Ahmad", "Nasser", "PA123")));
        BookingRequest r2 = request(otherFlight, FARE_CLASS_ID,
                List.of(passenger("Ahmad", "Nasser", "PA123")));

        assertThat(hashService.computeHash(r1)).isNotEqualTo(hashService.computeHash(r2));
    }

    @Test
    @DisplayName("different fareClassId → different hash")
    void differentFareClassId_shouldProduceDifferentHash() {
        UUID otherFare = UUID.fromString("dddd0000-0000-0000-0000-000000000000");
        BookingRequest r1 = request(FLIGHT_ID, FARE_CLASS_ID,
                List.of(passenger("Ahmad", "Nasser", "PA123")));
        BookingRequest r2 = request(FLIGHT_ID, otherFare,
                List.of(passenger("Ahmad", "Nasser", "PA123")));

        assertThat(hashService.computeHash(r1)).isNotEqualTo(hashService.computeHash(r2));
    }

    @Test
    @DisplayName("different passenger passport → different hash")
    void differentPassenger_shouldProduceDifferentHash() {
        BookingRequest r1 = request(FLIGHT_ID, FARE_CLASS_ID,
                List.of(passenger("Ahmad", "Nasser", "PA123")));
        BookingRequest r2 = request(FLIGHT_ID, FARE_CLASS_ID,
                List.of(passenger("Ahmad", "Nasser", "PA999")));

        assertThat(hashService.computeHash(r1)).isNotEqualTo(hashService.computeHash(r2));
    }

    @Test
    @DisplayName("passenger list order-independent (same passengers, different order → same hash)")
    void passengerOrderIndependent_shouldProduceSameHash() {
        PassengerRequest p1 = passenger("Ahmad", "Nasser", "PA001");
        PassengerRequest p2 = passenger("Sara", "Khalil", "PA002");

        BookingRequest r1 = request(FLIGHT_ID, FARE_CLASS_ID, List.of(p1, p2));
        BookingRequest r2 = request(FLIGHT_ID, FARE_CLASS_ID, List.of(p2, p1));

        assertThat(hashService.computeHash(r1)).isEqualTo(hashService.computeHash(r2));
    }

    @Test
    @DisplayName("passport number case-normalized (uppercase) → same hash")
    void passportNumberCaseNormalized_shouldProduceSameHash() {
        BookingRequest r1 = request(FLIGHT_ID, FARE_CLASS_ID,
                List.of(passenger("Ahmad", "Nasser", "pa123")));
        BookingRequest r2 = request(FLIGHT_ID, FARE_CLASS_ID,
                List.of(passenger("Ahmad", "Nasser", "PA123")));

        assertThat(hashService.computeHash(r1)).isEqualTo(hashService.computeHash(r2));
    }

    @Test
    @DisplayName("nationality case-normalized → same hash")
    void nationalityCaseNormalized_shouldProduceSameHash() {
        BookingRequest r1 = request(FLIGHT_ID, FARE_CLASS_ID,
                List.of(passenger("Ahmad", "Nasser", "PA123")));

        // Build second request manually with lowercase nationality
        PassengerRequest p2 = PassengerRequest.builder()
                .firstName("Ahmad")
                .lastName("Nasser")
                .passportNumber("PA123")
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .nationality("jo")    // lowercase
                .build();
        BookingRequest r2 = request(FLIGHT_ID, FARE_CLASS_ID, List.of(p2));

        assertThat(hashService.computeHash(r1)).isEqualTo(hashService.computeHash(r2));
    }

    @Test
    @DisplayName("firstName whitespace trimmed → same hash")
    void whitespaceInFirstName_shouldProduceSameHash() {
        PassengerRequest withSpaces = PassengerRequest.builder()
                .firstName("  Ahmad  ")
                .lastName("Nasser")
                .passportNumber("PA123")
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .nationality("JO")
                .build();

        BookingRequest r1 = request(FLIGHT_ID, FARE_CLASS_ID,
                List.of(passenger("Ahmad", "Nasser", "PA123")));
        BookingRequest r2 = request(FLIGHT_ID, FARE_CLASS_ID, List.of(withSpaces));

        assertThat(hashService.computeHash(r1)).isEqualTo(hashService.computeHash(r2));
    }

    @Test
    @DisplayName("hash is exactly 64 hex characters (SHA-256 output)")
    void hash_shouldBe64HexCharacters() {
        BookingRequest r = request(FLIGHT_ID, FARE_CLASS_ID,
                List.of(passenger("Ahmad", "Nasser", "PA123")));
        String hash = hashService.computeHash(r);
        assertThat(hash).matches("[0-9a-f]{64}");
    }
}
