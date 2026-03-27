package com.team01.uber.ride.repository;

import com.team01.uber.ride.enums.RideStatus;
import com.team01.uber.ride.model.Ride;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface RideRepository extends JpaRepository<Ride, Long> {

    List<Ride> findByRequestedAtBetweenOrderByRequestedAtDesc(LocalDateTime start, LocalDateTime end);

    @Query("SELECT r FROM Ride r WHERE r.requestedAt BETWEEN :start AND :end AND r.status = :status ORDER BY r.requestedAt DESC")
    List<Ride> findByRequestedAtBetweenAndStatusOrderByRequestedAtDesc(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("status") RideStatus status
    );
}
