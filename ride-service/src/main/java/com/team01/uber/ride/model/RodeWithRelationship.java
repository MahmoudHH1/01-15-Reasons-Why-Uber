package com.team01.uber.ride.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.RelationshipProperties;
import org.springframework.data.neo4j.core.schema.TargetNode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@RelationshipProperties
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RodeWithRelationship {

    @Id
    @GeneratedValue
    private Long id;

    @TargetNode
    private DriverNode driver;

    private Integer rideCount;

    private LocalDateTime lastRideDate;

    // Idempotency: tracks which rideIds have already been recorded for this edge
    private List<Long> recordedRideIds = new ArrayList<>();
}
