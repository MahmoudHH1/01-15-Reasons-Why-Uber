package com.team01.uber.ride.controller;

import com.team01.uber.contracts.dto.DriverRideSummaryDTO;
import com.team01.uber.contracts.dto.RideSummaryDTO;
import com.team01.uber.ride.dto.*;
import com.team01.uber.ride.enums.RideStatus;
import com.team01.uber.ride.model.Ride;
import com.team01.uber.ride.security.AuthContext;
import com.team01.uber.ride.service.RecommendationService;
import com.team01.uber.ride.service.RideService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rides")
public class RideController {

    private final RideService rideService;
    private final RecommendationService recommendationService;

    public RideController(RideService rideService, RecommendationService recommendationService) {
        this.rideService = rideService;
        this.recommendationService = recommendationService;
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }

    @PostMapping("/estimate")
    public ResponseEntity<FareEstimateDTO> estimateFare(@RequestBody FareEstimateRequestDTO request) {
        return ResponseEntity.ok(rideService.estimateFare(request));
    }

    @PostMapping
    public ResponseEntity<Ride> createRide(@RequestBody Ride ride) {
        return ResponseEntity.status(HttpStatus.CREATED).body(rideService.createRide(ride));
    }

    @GetMapping("/{id}")
    public Ride getRideById(@PathVariable Long id) {
        return rideService.getRideById(id);
    }

    @GetMapping
    public List<Ride> getAllRides() {
        return rideService.getAllRides();
    }

    @GetMapping("/search")
    public List<Ride> searchRides(
            @RequestParam(required = false) RideStatus status,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @RequestParam(required = false) Long userId) {
        return rideService.searchRides(status, startDate, endDate, userId);
    }

    @PutMapping("/{id}")
    public Ride updateRide(@PathVariable Long id, @RequestBody Ride ride) {
        return rideService.updateRide(id, ride);
    }

    @GetMapping("/{rideId}/details")
    public ResponseEntity<RideDetailsDTO> getRideDetails(@PathVariable Long rideId) {
        return ResponseEntity.ok(rideService.getRideDetails(rideId));
    }

    @PutMapping("/{id}/cancel")
    public ResponseEntity<Ride> cancelRide(@PathVariable Long id) {
        return ResponseEntity.ok(rideService.cancelRide(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRide(@PathVariable Long id) {
        rideService.deleteRide(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/analytics")
    public ResponseEntity<RideAnalyticsDTO> getAnalytics(
            @RequestParam String startDate,
            @RequestParam String endDate) {

        return ResponseEntity.ok(rideService.getRideAnalytics(startDate, endDate));
    }

    @GetMapping("/metadata/search")
    public List<Ride> searchByMetadata(
            @RequestParam("key") String key,
            @RequestParam("value") String value) {
        return rideService.findByMetadata(key, value);
    }

    @PutMapping("/{id}/complete")
    public ResponseEntity<Ride> completeRide(@PathVariable Long id) {
        Ride completedRide = rideService.completeRide(id);
        return ResponseEntity.ok(completedRide);
    }
    
    @PutMapping("/{id}/assign")
    public Ride assignDriver(@PathVariable Long id, @RequestParam Long driverId) {
        return rideService.assignDriver(id, driverId);
    }

    @PostMapping("/{rideId}/record-interaction")
    public ResponseEntity<Map<String, String>> recordInteraction(@PathVariable Long rideId) {
        String message = rideService.recordInteraction(rideId);
        return ResponseEntity.ok(Map.of("message", message));
    }
  
    @GetMapping("/analytics/dashboard")
    public ResponseEntity<RideAnalyticsDashboardDTO> getAnalyticsDashboard(
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        RideAnalyticsDashboardDTO dashboard = rideService.getRideAnalyticsDashboard(startDate, endDate);
        rideService.logDashboardViewed(startDate, endDate);
        return ResponseEntity.ok(dashboard);
    }

    @GetMapping("/recommendations")
    public ResponseEntity<List<DriverRecommendationDTO>> getRecommendations(
            @RequestParam Long userId,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        AuthContext ctx = (AuthContext) request.getAttribute("authContext");
        List<DriverRecommendationDTO> recommendations = recommendationService.getRecommendations(
                userId, ctx.getUserId(), ctx.getRole(), limit);
        return ResponseEntity.ok(recommendations);
    }

    @GetMapping("/user/{userId}/summary")
    public ResponseEntity<RideSummaryDTO> getUserRideSummary(@PathVariable Long userId, HttpServletRequest request) {
        ensureCallerIsTargetOrAdmin(userId, request, "user");
        return ResponseEntity.ok(rideService.getUserRideSummary(userId));
    }

    @GetMapping("/user/{userId}/active-count")
    public ResponseEntity<Integer> getActiveRideCountForUser(@PathVariable Long userId, HttpServletRequest request) {
        ensureCallerIsTargetOrAdmin(userId, request, "user");
        return ResponseEntity.ok(rideService.getActiveRideCountForUser(userId));
    }

    @GetMapping("/user/{userId}/completed-count")
    public ResponseEntity<Long> getCompletedRideCountForUser(@PathVariable Long userId, HttpServletRequest request) {
        ensureCallerIsTargetOrAdmin(userId, request, "user");
        return ResponseEntity.ok(rideService.getCompletedRideCountForUser(userId));
    }

    @GetMapping("/driver/{driverId}/summary")
    public ResponseEntity<DriverRideSummaryDTO> getDriverRideSummary(
            @PathVariable Long driverId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            HttpServletRequest request) {
        ensureCallerIsTargetOrAdmin(driverId, request, "driver");
        return ResponseEntity.ok(rideService.getDriverRideSummary(driverId, startDate, endDate));
    }

    @GetMapping("/driver/{driverId}/active-count")
    public ResponseEntity<Integer> getActiveRideCountForDriver(@PathVariable Long driverId, HttpServletRequest request) {
        ensureCallerIsTargetOrAdmin(driverId, request, "driver");
        return ResponseEntity.ok(rideService.getActiveRideCountForDriver(driverId));
    }

    @GetMapping("/driver/{driverId}/stats")
    public ResponseEntity<DriverRideSummaryDTO> getDriverStats(
            @PathVariable Long driverId,
            HttpServletRequest request) {
        ensureCallerIsTargetOrAdmin(driverId, request, "driver");
        return ResponseEntity.ok(rideService.getDriverRideSummary(driverId, null, null));
    }

    @GetMapping("/driver/{driverId}/completed-count")
    public ResponseEntity<Long> getCompletedRideCountForDriver(@PathVariable Long driverId, HttpServletRequest request) {
        ensureCallerIsTargetOrAdmin(driverId, request, "driver");
        return ResponseEntity.ok(rideService.getCompletedRideCountForDriver(driverId));
    }

    private void ensureCallerIsTargetOrAdmin(Long targetId, HttpServletRequest request, String targetType) {
        AuthContext ctx = (AuthContext) request.getAttribute("authContext");
        if (ctx == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authentication context");
        }

        boolean isAdmin = "ADMIN".equals(ctx.getRole());
        if (!isAdmin && !targetId.equals(ctx.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Caller is not the target " + targetType + " or an ADMIN");
        }
    }
}
