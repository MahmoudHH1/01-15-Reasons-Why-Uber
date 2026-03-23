package com.team01.uber.driver.repository;

import com.team01.uber.driver.model.DriverDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DriverDocumentRepository extends JpaRepository<DriverDocument, Long> {

    List<DriverDocument> findByDriverId(Long driverId);
}
