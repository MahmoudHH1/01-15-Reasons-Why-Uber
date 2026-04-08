package com.team01.uber.user.repository;

import com.team01.uber.user.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserRepository extends JpaRepository<User, Long> {
    boolean existsByEmail(String email);
    boolean existsByPhone(String phone);

    @Query(value = """
            SELECT * FROM users
            WHERE (:name IS NULL OR LOWER(name) LIKE LOWER(CONCAT('%', :name, '%')))
            AND (:email IS NULL OR LOWER(email) LIKE LOWER(CONCAT('%', :email, '%')))
            AND (:role IS NULL OR role = :role)
            """, nativeQuery = true)
    List<User> searchUsers(@Param("name") String name, @Param("email") String email, @Param("role") String role);
}