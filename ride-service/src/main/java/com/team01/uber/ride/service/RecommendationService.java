package com.team01.uber.ride.service;

import com.team01.uber.ride.adapter.Neo4jRecordAdapter;
import com.team01.uber.ride.dto.DriverRecommendationDTO;
import com.team01.uber.ride.repository.RideRepository;
import com.team01.uber.ride.repository.UserNodeRepository;
import org.neo4j.driver.Record;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class RecommendationService {

    private static final int DEFAULT_LIMIT = 5;

    private final UserNodeRepository userNodeRepository;
    private final RideRepository rideRepository;
    private final Neo4jRecordAdapter neo4jRecordAdapter;
    private final RecommendationService self;

    public RecommendationService(UserNodeRepository userNodeRepository,
                                 RideRepository rideRepository,
                                 Neo4jRecordAdapter neo4jRecordAdapter,
                                 @Lazy RecommendationService self) {
        this.userNodeRepository = userNodeRepository;
        this.rideRepository = rideRepository;
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
        List<Record> records = userNodeRepository.findRecommendationsForUser(userId, limit);
        return records.stream()
                .map(neo4jRecordAdapter::adapt)
                .toList();
    }
}
