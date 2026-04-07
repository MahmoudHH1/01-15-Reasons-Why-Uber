package com.team01.uber.user.repository;

import com.team01.uber.user.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserRepository extends JpaRepository<User, Long> {
    boolean existsByEmail(String email);
    boolean existsByPhone(String phone);

    @Query(value = "SELECT * FROM users WHERE preferences->>:key = :value", nativeQuery = true)
    List<User> findByPreference(@Param("key") String key, @Param("value") String value);
}