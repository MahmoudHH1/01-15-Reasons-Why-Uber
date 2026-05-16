package com.team01.uber.location.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;

@Table("location_tracking_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LocationTrackingEvent {

    @PrimaryKey
    private LocationTrackingEventKey key;

    @Column("latitude")
    private Double latitude;

    @Column("longitude")
    private Double longitude;

    @Column("speed")
    private Double speed;

    @Column("heading")
    private Double heading;

    @Column("accuracy")
    private Double accuracy;

    @Column("ride_id")
    private Long rideId;

    @Column("notes")
    private String notes;

    public Long getDriverId() {
        return key != null ? key.getDriverId() : null;
    }

    public void setDriverId(Long driverId) {
        if (key == null) key = new LocationTrackingEventKey();
        key.setDriverId(driverId);
    }

    public Instant getTimestamp() {
        return key != null ? key.getTimestamp() : null;
    }

    public void setTimestamp(Instant timestamp) {
        if (key == null) key = new LocationTrackingEventKey();
        key.setTimestamp(timestamp);
    }
}
