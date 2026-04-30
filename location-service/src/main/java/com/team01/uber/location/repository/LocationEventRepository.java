package com.team01.uber.location.repository;

import com.team01.uber.location.model.LocationEvent;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LocationEventRepository extends MongoRepository<LocationEvent, String> {

    List<LocationEvent> findByDriverId(Long driverId);
}
