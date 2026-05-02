package com.team01.uber.ride.service;

import com.team01.uber.ride.adapter.Neo4jRecordAdapter;
import com.team01.uber.ride.dto.DriverRecommendationDTO;
import com.team01.uber.ride.repository.RideRepository;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Values;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class RecommendationService {

    private static final int DEFAULT_LIMIT = 5;
    private static final String RECOMMENDATIONS_CYPHER = """
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
            """;

    private final RideRepository rideRepository;
    private final Driver neo4jDriver;
    private final Neo4jRecordAdapter neo4jRecordAdapter;
    private final RecommendationService self;

    public RecommendationService(RideRepository rideRepository,
                                 Driver neo4jDriver,
                                 Neo4jRecordAdapter neo4jRecordAdapter,
                                 @Lazy RecommendationService self) {
        this.rideRepository = rideRepository;
        this.neo4jDriver = neo4jDriver;
        this.neo4jRecordAdapter = neo4jRecordAdapter;
        this.self = self;
    }

    public List<DriverRecommendationDTO> getRecommendations(Long targetUserId,
                                                            Long callerUserId,
                                                            String callerRole,
                                                            Integer limit) {
        boolean isAdmin = "ADMIN".equals(callerRole);
        if (!isAdmin && !targetUserId.equals(callerUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Caller is not the target user or an ADMIN");
        }

        if (!rideRepository.userExists(targetUserId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }

        int effectiveLimit = (limit == null || limit <= 0) ? DEFAULT_LIMIT : limit;
        return self.loadRecommendations(targetUserId, effectiveLimit);
    }

    @Cacheable(value = "ride-service::S3-F12", key = "#userId + '-' + #limit")
    public List<DriverRecommendationDTO> loadRecommendations(Long userId, int limit) {
        try (var session = neo4jDriver.session()) {
            return session.run(RECOMMENDATIONS_CYPHER, Values.parameters("userId", userId, "limit", limit))
                    .list(neo4jRecordAdapter::adapt);
        }
    }
}
