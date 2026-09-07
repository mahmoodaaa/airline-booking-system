package com.project.flightservice.dto.request;

import com.project.flightservice.enums.AircraftStatus;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateAircraftStatusRequest {

    @NotNull
    private AircraftStatus status;
}
