package com.team01.uber.ride.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.ArrayList;
import java.util.List;

@Node("User")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserNode {
    @Id
    @Property("id")
    private Long userId;

    private String name;

    @Relationship(type = "RODE_WITH", direction = Relationship.Direction.OUTGOING)
    private List<RodeWithRelationship> rodeWithRelationships = new ArrayList<>();
}
