package com.team01.uber.user.repository;

import com.team01.uber.user.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface UserRepository extends JpaRepository<User, Long> {
    boolean existsByEmail(String email);
    boolean existsByPhone(String phone);

    @Query(value = """
        SELECT u.id AS userId, u.name AS name,
               SUM(r.fare) AS totalSpent,
               COUNT(r.id) AS rideCount
        FROM users u
        JOIN rides r ON r.user_id = u.id
        WHERE r.status = 'COMPLETED'
          AND r.requested_at BETWEEN :startDate AND :endDate
        GROUP BY u.id, u.name
        ORDER BY totalSpent DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> findTopRiders(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("limit") int limit);
}