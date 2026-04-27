package com.team01.uber.location.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;

@Table("location_tracking_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LocationTrackingEvent {

    @PrimaryKeyColumn(name = "driver_id", type = PrimaryKeyType.PARTITIONED)
    private Long driverId;

    @PrimaryKeyColumn(name = "timestamp", type = PrimaryKeyType.CLUSTERED, ordering = Ordering.DESCENDING)
    private Instant timestamp;

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
}
