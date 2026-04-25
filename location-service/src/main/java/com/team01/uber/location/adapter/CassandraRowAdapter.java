package com.team01.uber.location.adapter;

import com.team01.uber.location.dto.LocationTrackingDTO;
import com.team01.uber.location.model.LocationTrackingEvent;

public class CassandraRowAdapter {

    private CassandraRowAdapter() {}

    public static LocationTrackingDTO adapt(LocationTrackingEvent event) {
        return new LocationTrackingDTO(
                event.getKey().getDriverId(),
                event.getKey().getEventTimestamp(),
                event.getLatitude(),
                event.getLongitude(),
                event.getSpeed(),
                event.getHeading(),
                event.getAccuracy(),
                event.getRideId(),
                event.getNotes()
        );
    }
}
