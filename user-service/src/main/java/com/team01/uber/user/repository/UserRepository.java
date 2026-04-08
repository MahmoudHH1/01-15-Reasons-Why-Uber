package com.team01.uber.user.repository;

import com.team01.uber.user.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {
    boolean existsByEmail(String email);
    boolean existsByPhone(String phone);

    @Query(value = """
            SELECT 
                u.id AS userId,
                u.name AS name,
                COUNT(r.id) AS totalRides,
                COUNT(CASE WHEN r.status = 'COMPLETED' THEN 1 END) AS completedRides,
                COUNT(CASE WHEN r.status = 'CANCELLED' THEN 1 END) AS cancelledRides,
                COALESCE(SUM(CASE WHEN r.status = 'COMPLETED' THEN r.fare ELSE 0 END), 0) AS totalSpent,
                COALESCE(AVG(CASE WHEN r.status = 'COMPLETED' THEN r.fare END), 0) AS averageFare
            FROM users u
            LEFT JOIN rides r ON r.rider_id = u.id
            WHERE u.id = :userId
            GROUP BY u.id, u.name
            """, nativeQuery = true)
    Object[] getRideSummary(@Param("userId") Long userId);
}