package com.project.bookingservice.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.project.bookingservice.dto.request.BookingRequest;
import com.project.bookingservice.dto.request.PassengerRequest;
import com.project.bookingservice.service.RequestHashService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RequestHashServiceImpl implements RequestHashService {

    private final ObjectMapper objectMapper;

    public RequestHashServiceImpl() {
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    @Override
    public String computeHash(BookingRequest request) {
        String normalized = normalize(request);
        return sha256(normalized);
    }

    private String normalize(BookingRequest request) {
        try {
            NormalizedRequest normalized = NormalizedRequest.from(request);
            return objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize request for hashing: {}", e.getMessage());
            throw new IllegalStateException("Failed to compute request hash", e);
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private record NormalizedRequest(String flightId, String fareClassId, List<NormalizedPassenger> passengers) {
        static NormalizedRequest from(BookingRequest req) {
            List<NormalizedPassenger> passengers = req.getPassengers()
                    .stream()
                    .map(NormalizedPassenger::from)
                    .sorted(Comparator
                            .comparing(NormalizedPassenger::passportNumber)
                            .thenComparing(NormalizedPassenger::lastName)
                            .thenComparing(NormalizedPassenger::firstName))
                    .collect(Collectors.toList());

            return new NormalizedRequest(
                    req.getFlightId().toString(),
                    req.getFareClassId().toString(),
                    passengers
            );
        }
    }

    private record NormalizedPassenger(String firstName, String lastName, String passportNumber,
            String dateOfBirth,
            String nationality
    ) {
        static NormalizedPassenger from(PassengerRequest p) {
            return new NormalizedPassenger(
                    trim(p.getFirstName()),
                    trim(p.getLastName()),
                    upper(p.getPassportNumber()),
                    p.getDateOfBirth() != null ? p.getDateOfBirth().toString() : "",
                    upper(p.getNationality())
            );
        }

        private static String trim(String s) { return s == null ? "" : s.trim(); }
        private static String upper(String s) { return s == null ? "" : s.trim().toUpperCase(); }
    }
}
