package com.project.flightservice.repository;

import com.project.flightservice.entity.Aircraft;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AircraftRepository extends JpaRepository<Aircraft, UUID> {
    boolean existsByRegistrationNumber(String registrationNumber);
    Optional<Aircraft> findByRegistrationNumber(String registrationNumber);
}
