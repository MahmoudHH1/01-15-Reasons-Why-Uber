package com.team01.uber.user.adapter;

import com.team01.uber.user.dto.TopRiderDTO;
import com.team01.uber.user.dto.UserRideSummaryDTO;

public class ObjectArrayDtoAdapter {

    public UserRideSummaryDTO adaptToUserRideSummary(Object[] data) {
        return UserRideSummaryDTO.builder()
                .userId(((Number) data[0]).longValue())
                .name((String) data[1])
                .totalRides(((Number) data[2]).longValue())
                .completedRides(((Number) data[3]).longValue())
                .cancelledRides(((Number) data[4]).longValue())
                .totalSpent(((Number) data[5]).doubleValue())
                .averageFare(((Number) data[6]).doubleValue())
                .build();
    }

    public TopRiderDTO adaptToTopRider(Object[] data) {
        return new TopRiderDTO(
                ((Number) data[0]).longValue(),
                (String) data[1],
                ((Number) data[2]).doubleValue(),
                ((Number) data[3]).longValue()
        );
    }
}