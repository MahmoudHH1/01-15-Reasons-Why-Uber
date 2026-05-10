package com.team01.uber.contracts.dto;

import java.time.LocalDateTime;

public record LocationDTO(
        Long driverId,
        Double latitude,
        Double longitude,
        LocalDateTime timestamp,
        Double speed
) {}
