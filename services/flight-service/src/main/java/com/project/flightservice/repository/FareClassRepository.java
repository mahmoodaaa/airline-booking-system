package com.project.flightservice.repository;

import com.project.flightservice.entity.FareClass;
import com.project.flightservice.enums.FareClassType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FareClassRepository extends JpaRepository<FareClass, UUID> {
    Optional<FareClass> findByFlightIdAndClassType(UUID flightId, FareClassType classType);
    List<FareClass> findByFlightId(UUID flightId);
}
