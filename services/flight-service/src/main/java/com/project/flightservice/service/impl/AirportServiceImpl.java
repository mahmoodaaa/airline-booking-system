package com.project.flightservice.service.impl;

import com.project.common.exception.ConflictException;
import com.project.common.exception.RecordNotFoundException;
import com.project.flightservice.dto.request.AirportRequest;
import com.project.flightservice.dto.request.UpdateAirportStatusRequest;
import com.project.flightservice.dto.response.AirportResponse;
import com.project.flightservice.entity.Airport;
import com.project.flightservice.enums.AirportStatus;
import com.project.flightservice.mapper.AirportMapper;
import com.project.flightservice.repository.AirportRepository;
import com.project.flightservice.service.AirportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AirportServiceImpl implements AirportService {

    private final AirportRepository airportRepository;
    private final AirportMapper airportMapper;

    @Override
    @Transactional
    public AirportResponse createAirport(AirportRequest request) {
        if (airportRepository.existsByIataCode(request.getIataCode().toUpperCase())) {
            throw new ConflictException("Airport with IATA code " + request.getIataCode().toUpperCase() + " already exists");
        }

        Airport airport = airportMapper.toEntity(request);
        Airport savedAirport = airportRepository.save(airport);
        return airportMapper.toResponse(savedAirport);
    }

    @Override
    @Transactional(readOnly = true)
    public AirportResponse getAirportById(UUID id) {
        Airport airport = findAirportEntityById(id);
        return airportMapper.toResponse(airport);
    }

    @Override
    @Transactional(readOnly = true)
    public AirportResponse getAirportByIata(String iataCode) {
        Airport airport = airportRepository.findByIataCode(iataCode.toUpperCase())
                .orElseThrow(() -> new RecordNotFoundException("Airport not found with IATA code: " + iataCode));
        return airportMapper.toResponse(airport);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AirportResponse> getAllAirports() {
        return airportRepository.findAll().stream()
                .map(airportMapper::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public AirportResponse updateAirport(UUID id, AirportRequest request) {
        Airport airport = findAirportEntityById(id);

        if (!airport.getIataCode().equalsIgnoreCase(request.getIataCode())) {
            if (airportRepository.existsByIataCode(request.getIataCode().toUpperCase())) {
                throw new ConflictException("Airport with IATA code " + request.getIataCode().toUpperCase() + " already exists");
            }
        }

        airport.setIataCode(request.getIataCode().toUpperCase());
        airport.setIcaoCode(request.getIcaoCode() != null ? request.getIcaoCode().toUpperCase() : null);
        airport.setName(request.getName());
        airport.setCity(request.getCity());
        airport.setCountry(request.getCountry());
        airport.setTimezone(request.getTimezone());

        Airport updatedAirport = airportRepository.save(airport);
        return airportMapper.toResponse(updatedAirport);
    }

    @Override
    @Transactional
    public AirportResponse updateStatus(UUID id, UpdateAirportStatusRequest request) {
        Airport airport = findAirportEntityById(id);
        airport.setStatus(request.getStatus());
        return airportMapper.toResponse(airportRepository.save(airport));
    }

    @Override
    @Transactional
    public void deleteAirport(UUID id) {
        Airport airport = findAirportEntityById(id);
        airport.setStatus(AirportStatus.INACTIVE);
        airportRepository.save(airport);
    }

    private Airport findAirportEntityById(UUID id) {
        return airportRepository.findById(id)
                .orElseThrow(() -> new RecordNotFoundException("Airport not found with ID: " + id));
    }

}
