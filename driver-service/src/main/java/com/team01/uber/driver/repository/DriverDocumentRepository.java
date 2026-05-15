package com.team01.uber.driver.repository;

import com.team01.uber.driver.model.DriverDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DriverDocumentRepository extends JpaRepository<DriverDocument, Long> {

    List<DriverDocument> findByDriverId(Long driverId);

    Optional<DriverDocument> findByIdAndDriverId(Long id, Long driverId);

    boolean existsByIdAndDriverId(Long id, Long driverId);

    List<DriverDocument> findByExpiryDateBefore(LocalDate date);
}
