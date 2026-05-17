package com.team01.uber.ride.service;

import com.team01.uber.contracts.dto.DriverDTO;
import com.team01.uber.contracts.feign.DriverServiceClient;
import com.team01.uber.contracts.feign.UserServiceClient;
import com.team01.uber.ride.adapter.Neo4jRecordAdapter;
import com.team01.uber.ride.dto.DriverRecommendationDTO;
import feign.FeignException;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 100;
    private static final String RECOMMENDATIONS_CYPHER = """
            MATCH (target:User {id: $userId})-[:RODE_WITH]->(shared:Driver)
                  <-[:RODE_WITH]-(other:User)
            WHERE other.id <> $userId
            MATCH (other)-[:RODE_WITH]->(rec:Driver)
            WHERE NOT (target)-[:RODE_WITH]->(rec)
            RETURN rec.id          AS driverId,
                   rec.name        AS name,
                   rec.vehicleType AS vehicleType,
                   count(DISTINCT other) AS score
            ORDER BY score DESC
            LIMIT $limit
            """;

    private final Driver neo4jDriver;
    private final Neo4jRecordAdapter neo4jRecordAdapter;
    private final DriverServiceClient driverServiceClient;
    private final UserServiceClient userServiceClient;
    private final RecommendationService self;

    public RecommendationService(Driver neo4jDriver,
                                 Neo4jRecordAdapter neo4jRecordAdapter,
                                 DriverServiceClient driverServiceClient,
                                 UserServiceClient userServiceClient,
                                 @Lazy RecommendationService self) {
        this.neo4jDriver = neo4jDriver;
        this.neo4jRecordAdapter = neo4jRecordAdapter;
        this.driverServiceClient = driverServiceClient;
        this.userServiceClient = userServiceClient;
        this.self = self;
    }

    public List<DriverRecommendationDTO> getRecommendations(Long targetUserId,
                                                            Long callerUserId,
                                                            String callerRole,
                                                            Integer limit) {
        boolean isAdmin = "ADMIN".equals(callerRole);
        if (!isAdmin && !targetUserId.equals(callerUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Caller is not the target user or an ADMIN");
        }

        // M3: replace rideRepository.userExists() with Feign → user-service (§5 S3-F12)
        log.info("Calling userServiceClient.getUser with args={}", targetUserId);
        try {
            userServiceClient.getUser(targetUserId);
            log.info("userServiceClient.getUser returned successfully");
        } catch (FeignException.NotFound e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        } catch (FeignException e) {
            log.warn("Feign call to user-service failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "User service temporarily unavailable");
        }

        int effectiveLimit = (limit == null || limit <= 0) ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        return self.loadRecommendations(targetUserId, effectiveLimit);
    }

    @Cacheable(value = "ride-service::S3-F12", key = "#userId + '-' + #limit")
    public List<DriverRecommendationDTO> loadRecommendations(Long userId, int limit) {
        try (var session = neo4jDriver.session()) {
            List<DriverRecommendationDTO> fromGraph = session
                    .run(RECOMMENDATIONS_CYPHER, Values.parameters("userId", userId, "limit", limit))
                    .list(neo4jRecordAdapter::adapt);
            return fromGraph.stream().map(this::overrideFromDriverService).toList();
        } catch (Exception e) {
            log.warn("Neo4j unavailable for driver recommendations (userId={}): {}", userId, e.getMessage());
            return List.of();
        }
    }

    // M3: replace rideRepository.findDriverNameById() + findDriverVehicleTypeById()
    // with Feign → driver-service (§5 S3-F12)
    private DriverRecommendationDTO overrideFromDriverService(DriverRecommendationDTO graphDto) {
        String resolvedName = graphDto.getName();
        String resolvedVehicleType = graphDto.getVehicleType();

        log.info("Calling driverServiceClient.getDriver with args={}", graphDto.getDriverId());
        try {
            DriverDTO driver = driverServiceClient.getDriver(graphDto.getDriverId());
            log.info("driverServiceClient.getDriver returned successfully");
            resolvedName = driver.name() != null ? driver.name() : resolvedName;
            String pgVehicleType = (String) driver.vehicleDetails().getOrDefault("vehicleType", "");
            resolvedVehicleType = pgVehicleType != null && !pgVehicleType.isBlank()
                    ? pgVehicleType
                    : (resolvedVehicleType != null && !resolvedVehicleType.isBlank()
                    ? resolvedVehicleType
                    : "UNKNOWN");
        } catch (FeignException.NotFound e) {
            log.warn("Feign call to driver-service failed: driver {} not found", graphDto.getDriverId());
            resolvedVehicleType = resolvedVehicleType != null && !resolvedVehicleType.isBlank()
                    ? resolvedVehicleType : "UNKNOWN";
        } catch (FeignException e) {
            log.warn("Feign call to driver-service failed: {}", e.getMessage());
            resolvedVehicleType = resolvedVehicleType != null && !resolvedVehicleType.isBlank()
                    ? resolvedVehicleType : "UNKNOWN";
        }

        return DriverRecommendationDTO.builder()
                .driverId(graphDto.getDriverId())
                .name(resolvedName)
                .vehicleType(resolvedVehicleType)
                .score(graphDto.getScore())
                .build();
    }
}