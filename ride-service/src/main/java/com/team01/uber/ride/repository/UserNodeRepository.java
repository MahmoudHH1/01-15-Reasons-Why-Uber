package com.team01.uber.ride.repository;

import com.team01.uber.ride.model.UserNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;

public interface UserNodeRepository extends Neo4jRepository<UserNode, Long> {
}
