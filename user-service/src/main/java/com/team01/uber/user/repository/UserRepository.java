package com.team01.uber.user.repository;

import com.team01.uber.user.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    boolean existsByEmail(String email);
    boolean existsByPhone(String phone);
    Optional<User> findByEmail(String email);

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
            LEFT JOIN rides r ON r.user_id = u.id
            WHERE u.id = :userId
            GROUP BY u.id, u.name
            """, nativeQuery = true)
    Object[] getRideSummary(@Param("userId") Long userId);

    @Query(value = """
            SELECT * FROM users
            WHERE (:name IS NULL OR LOWER(name) LIKE LOWER(CONCAT('%', :name, '%')))
            AND (:email IS NULL OR LOWER(email) LIKE LOWER(CONCAT('%', :email, '%')))
            AND (:role IS NULL OR role = CAST(:role AS user_role))
            """, nativeQuery = true)
    List<User> searchUsers(@Param("name") String name, @Param("email") String email, @Param("role") String role);

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

    @Query(value = "SELECT * FROM users WHERE preferences->>:key = :value", nativeQuery = true)
    List<User> findByPreference(@Param("key") String key, @Param("value") String value);

    // §2.12 candidate-set cap at the local-DB query stage (LIMIT 100).
    // Used by S1-F6 top-riders flow before the per-user Feign fan-out to payment-service.
    @Query(value = "SELECT * FROM users LIMIT 100", nativeQuery = true)
    List<User> findCandidateUsersCapped();

    // §2.12 candidate-set cap at the local-DB query stage (LIMIT 100).
    // Used by S1-F9 language-preference flow before the per-user Feign fan-out to ride-service.
    @Query(value = "SELECT * FROM users WHERE preferences->>:key = :value LIMIT 100", nativeQuery = true)
    List<User> findByPreferenceCapped(@Param("key") String key, @Param("value") String value);

    @Query(value = "SELECT COUNT(*) FROM rides WHERE user_id = :userId AND status IN ('REQUESTED', 'ACCEPTED', 'IN_PROGRESS')", nativeQuery = true)
    int countActiveRides(@Param("userId") Long userId);

    @Query(value = """
        SELECT u.* FROM users u
        WHERE u.preferences->>'language' = :lang
        AND (
            SELECT COUNT(*) FROM rides r
            WHERE r.user_id = u.id AND r.status = 'COMPLETED'
        ) >= :minRides
        """, nativeQuery = true)
    List<User> findByLanguagePreferenceWithMinRides(
            @Param("lang") String lang,
            @Param("minRides") int minRides);
}