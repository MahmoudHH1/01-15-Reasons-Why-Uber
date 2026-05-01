package com.team01.uber.ride.service;

import com.team01.uber.ride.adapter.Neo4jRecordAdapter;
import com.team01.uber.ride.dto.DriverRecommendationDTO;
import com.team01.uber.ride.repository.RideRepository;
import com.team01.uber.ride.repository.UserNodeRepository;
import org.neo4j.driver.Record;
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

    public RecommendationService(UserNodeRepository userNodeRepository,
                                 RideRepository rideRepository,
                                 Neo4jRecordAdapter neo4jRecordAdapter) {
        this.userNodeRepository = userNodeRepository;
        this.rideRepository = rideRepository;
        this.neo4jRecordAdapter = neo4jRecordAdapter;
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

        List<Record> records = userNodeRepository.findRecommendationsForUser(targetUserId, effectiveLimit);
        return records.stream()
                .map(neo4jRecordAdapter::adapt)
                .toList();
    }
}
