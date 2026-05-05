package com.team01.uber.ride.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;

@Node("Driver")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DriverNode {
    @Id
    @Property("id")
    private Long driverId;

    private String name;

    private String vehicleType;
}
