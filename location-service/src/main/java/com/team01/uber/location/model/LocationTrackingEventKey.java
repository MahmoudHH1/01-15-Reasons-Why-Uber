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

    @PrimaryKeyColumn(name = "timestamp", type = PrimaryKeyType.CLUSTERED, ordering = Ordering.DESCENDING)
    private Instant timestamp;

    public LocationTrackingEventKey() {}

    public LocationTrackingEventKey(Long driverId, Instant timestamp) {
        this.driverId = driverId;
        this.timestamp = timestamp;
    }

    public Long getDriverId() { return driverId; }
    public void setDriverId(Long driverId) { this.driverId = driverId; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LocationTrackingEventKey that)) return false;
        return Objects.equals(driverId, that.driverId) && Objects.equals(timestamp, that.timestamp);
    }

    @Override
    public int hashCode() { return Objects.hash(driverId, timestamp); }
}
