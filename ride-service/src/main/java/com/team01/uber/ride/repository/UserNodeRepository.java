package com.team01.uber.ride.repository;

import com.team01.uber.ride.model.UserNode;
import org.neo4j.driver.Record;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserNodeRepository extends Neo4jRepository<UserNode, Long> {

    @Query("""
            MATCH (target:UserNode {userId: $userId})-[:RODE_WITH]->(shared:DriverNode)
                  <-[:RODE_WITH]-(other:UserNode)
            WHERE other.userId <> $userId
            MATCH (other)-[:RODE_WITH]->(rec:DriverNode)
            WHERE NOT (target)-[:RODE_WITH]->(rec)
            RETURN rec.driverId    AS driverId,
                   rec.name        AS name,
                   rec.vehicleType AS vehicleType,
                   count(DISTINCT other) AS score
            ORDER BY score DESC
            LIMIT $limit
            """)
    List<Record> findRecommendationsForUser(@Param("userId") Long userId,
                                            @Param("limit") int limit);
}
