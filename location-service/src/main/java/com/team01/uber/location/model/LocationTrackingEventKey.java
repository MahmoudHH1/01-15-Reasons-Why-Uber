package com.team01.uber.location.model;

import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyClass;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@PrimaryKeyClass
public class LocationTrackingEventKey implements Serializable {

    @PrimaryKeyColumn(name = "driver_id", type = PrimaryKeyType.PARTITIONED)
    private Long driverId;

    @PrimaryKeyColumn(name = "event_timestamp", type = PrimaryKeyType.CLUSTERED, ordering = Ordering.DESCENDING)
    private Instant eventTimestamp;

    public LocationTrackingEventKey() {}

    public LocationTrackingEventKey(Long driverId, Instant eventTimestamp) {
        this.driverId = driverId;
        this.eventTimestamp = eventTimestamp;
    }

    public Long getDriverId() { return driverId; }
    public void setDriverId(Long driverId) { this.driverId = driverId; }
    public Instant getEventTimestamp() { return eventTimestamp; }
    public void setEventTimestamp(Instant eventTimestamp) { this.eventTimestamp = eventTimestamp; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LocationTrackingEventKey that)) return false;
        return Objects.equals(driverId, that.driverId) && Objects.equals(eventTimestamp, that.eventTimestamp);
    }

    @Override
    public int hashCode() { return Objects.hash(driverId, eventTimestamp); }
}
