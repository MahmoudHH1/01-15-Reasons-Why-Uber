package com.team01.uber.driver.repository;

import com.team01.uber.driver.model.DriverEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface DriverEventRepository extends MongoRepository<DriverEvent, String> {
    List<DriverEvent> findByDriverId(Long driverId);
}
