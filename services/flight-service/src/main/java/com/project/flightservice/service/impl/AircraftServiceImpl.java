package com.project.flightservice.service.impl;

import com.project.common.exception.ConflictException;
import com.project.common.exception.RecordNotFoundException;
import com.project.flightservice.dto.request.AircraftRequest;
import com.project.flightservice.dto.request.UpdateAircraftStatusRequest;
import com.project.flightservice.dto.response.AircraftResponse;
import com.project.flightservice.entity.Aircraft;
import com.project.flightservice.mapper.AircraftMapper;
import com.project.flightservice.repository.AircraftRepository;
import com.project.flightservice.service.AircraftService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AircraftServiceImpl implements AircraftService {

    private final AircraftRepository aircraftRepository;
    private final AircraftMapper aircraftMapper;

    @Override
    @Transactional
    public AircraftResponse createAircraft(AircraftRequest request) {
        if (aircraftRepository.existsByRegistrationNumber(request.getRegistrationNumber().toUpperCase())) {
            throw new ConflictException("Aircraft with registration number " + request.getRegistrationNumber().toUpperCase() + " already exists");
        }

        Aircraft aircraft = aircraftMapper.toEntity(request);
        Aircraft savedAircraft = aircraftRepository.save(aircraft);
        return aircraftMapper.toResponse(savedAircraft);
    }

    @Override
    @Transactional(readOnly = true)
    public AircraftResponse getAircraftById(UUID id) {
        Aircraft aircraft = findAircraftEntityById(id);
        return aircraftMapper.toResponse(aircraft);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AircraftResponse> getAllAircraft() {
        return aircraftRepository.findAll().stream()
                .map(aircraftMapper::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public AircraftResponse updateAircraft(UUID id, AircraftRequest request) {
        Aircraft aircraft = findAircraftEntityById(id);

        if (!aircraft.getRegistrationNumber().equalsIgnoreCase(request.getRegistrationNumber())) {
            if (aircraftRepository.existsByRegistrationNumber(request.getRegistrationNumber().toUpperCase())) {
                throw new ConflictException("Aircraft with registration number " + request.getRegistrationNumber().toUpperCase() + " already exists");
            }
        }

        aircraft.setRegistrationNumber(request.getRegistrationNumber().toUpperCase());
        aircraft.setManufacturer(request.getManufacturer());
        aircraft.setModel(request.getModel());
        aircraft.setTotalCapacity(request.getTotalCapacity());

        Aircraft updatedAircraft = aircraftRepository.save(aircraft);
        return aircraftMapper.toResponse(updatedAircraft);
    }

    @Override
    @Transactional
    public AircraftResponse updateStatus(UUID id, UpdateAircraftStatusRequest request) {
        Aircraft aircraft = findAircraftEntityById(id);
        aircraft.setStatus(request.getStatus());
        return aircraftMapper.toResponse(aircraftRepository.save(aircraft));
    }

    private Aircraft findAircraftEntityById(UUID id) {
        return aircraftRepository.findById(id)
                .orElseThrow(() -> new RecordNotFoundException("Aircraft not found with ID: " + id));
    }
}
