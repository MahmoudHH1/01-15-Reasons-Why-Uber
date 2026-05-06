package com.team01.uber.location.adapter;

import com.team01.uber.location.dto.LocationAnalyticsDTO;
import com.team01.uber.location.dto.LocationTrackingDTO;
import com.team01.uber.location.model.LocationTrackingEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LocationAdapter {

    public LocationTrackingDTO adaptToLocationTrackingDTO(LocationTrackingEvent event) {
        return new LocationTrackingDTO(
                event.getTimestamp(),
                event.getLatitude(),
                event.getLongitude(),
                event.getSpeed(),
                event.getHeading(),
                event.getAccuracy(),
                event.getRideId(),
                event.getNotes()
        );
    }

    public LocationAnalyticsDTO adaptToLocationAnalytics(Object[] stats, List<Object[]> hourlyData) {
        Map<Integer, Long> eventsByHour = new HashMap<>();
        for (Object[] row : hourlyData) {
            int hour = ((Number) row[0]).intValue();
            long count = ((Number) row[1]).longValue();
            if (count > 0) {
                eventsByHour.put(hour, count);
            }
        }

        return LocationAnalyticsDTO.builder()
                .totalLocationEvents(((Number) stats[0]).longValue())
                .activeDrivers(((Number) stats[1]).longValue())
                .averageSpeed(((Number) stats[2]).doubleValue())
                .eventsByHour(eventsByHour)
                .build();
    }
}
