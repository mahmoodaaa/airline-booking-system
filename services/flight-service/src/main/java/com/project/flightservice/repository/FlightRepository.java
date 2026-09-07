package com.project.flightservice.repository;

import com.project.flightservice.entity.Flight;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface FlightRepository extends JpaRepository<Flight, UUID> {
    List<Flight> findByOriginAirport_IataCodeAndDestinationAirport_IataCodeAndDepartureTimeBetween(
            String origin, String destination, LocalDateTime start, LocalDateTime end
    );
    boolean existsByFlightNumber(String flightNumber);
}
