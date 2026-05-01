package com.team01.uber.ride.adapter;

import com.team01.uber.ride.dto.DriverRecommendationDTO;
import org.neo4j.driver.Record;
import org.springframework.stereotype.Component;

/**
 * Adapter to convert a raw Neo4j Record into a DriverRecommendationDTO.
 * Used for S3-F12 to map the graph query results.
 */
@Component
public class Neo4jRecordAdapter {

    public DriverRecommendationDTO adapt(Record source) {
        if (source == null) {
            return null;
        }

        // Extracting values based on the expected Cypher query aliases
        // Note: Ensure your Cypher query in the repository uses these exact aliases:
        // e.g., RETURN driver.driverId AS driverId, driver.name AS name, driver.vehicleType AS vehicleType, count(similar_user) AS score

        Long driverId = source.get("driverId").asLong();
        String name = source.get("name").asString();
        String vehicleType = source.get("vehicleType").asString();
        int score = source.get("score").asInt();

        // Using the Builder pattern as mandated by the M2 spec
        return DriverRecommendationDTO.builder()
                .driverId(driverId)
                .name(name)
                .vehicleType(vehicleType)
                .score(score)
                .build();
    }
}