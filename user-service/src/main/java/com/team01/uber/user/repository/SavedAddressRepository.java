package com.team01.uber.user.repository;

import com.team01.uber.user.model.SavedAddress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SavedAddressRepository extends JpaRepository<SavedAddress, Long> {
    List<SavedAddress> findByUserId(Long userId);
    Optional<SavedAddress> findByIdAndUserId(Long id, Long userId);
}