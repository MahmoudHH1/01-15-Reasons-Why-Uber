package com.team01.uber.ride.repository;

import com.team01.uber.ride.model.DriverNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;

public interface DriverNodeRepository extends Neo4jRepository<DriverNode, Long> {
}
