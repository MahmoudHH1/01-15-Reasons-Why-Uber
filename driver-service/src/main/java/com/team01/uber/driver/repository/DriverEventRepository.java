package com.team01.uber.driver.repository;

import com.team01.uber.driver.model.DriverEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface DriverEventRepository extends MongoRepository<DriverEvent, String> {
}
