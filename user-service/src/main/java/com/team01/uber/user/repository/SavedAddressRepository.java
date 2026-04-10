package com.team01.uber.user.repository;

import com.team01.uber.user.model.SavedAddress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;


import java.util.List;
import java.util.Optional;

public interface SavedAddressRepository extends JpaRepository<SavedAddress, Long> {
    List<SavedAddress> findByUserId(Long userId);
    Optional<SavedAddress> findByIdAndUserId(Long id, Long userId);

    @Modifying
    @Transactional
    @Query(value = "UPDATE saved_addresses SET is_default = false WHERE user_id = :userId", nativeQuery = true)
    void clearDefaultForUser(Long userId);
}