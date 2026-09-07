package com.project.flightservice.repository;

import com.project.flightservice.entity.Airport;
import com.project.flightservice.enums.AirportStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AirportRepository extends JpaRepository<Airport, UUID> {
    Optional<Airport> findByIataCode(String iataCode);
    boolean existsByIataCode(String iataCode);
    List<Airport> findByStatus(AirportStatus status);
}
