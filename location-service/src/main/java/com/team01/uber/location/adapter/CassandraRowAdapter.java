package com.team01.uber.location.adapter;

import com.team01.uber.location.dto.LocationTrackingDTO;
import com.team01.uber.location.model.LocationTrackingEvent;

public class CassandraRowAdapter {

    public LocationTrackingDTO adapt(LocationTrackingEvent event) {
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
}
