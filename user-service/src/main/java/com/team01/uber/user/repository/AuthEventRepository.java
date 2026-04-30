package com.team01.uber.user.repository;

import com.team01.uber.user.model.mongo.AuthEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AuthEventRepository extends MongoRepository<AuthEvent, String> {
    Page<AuthEvent> findByUserIdOrderByTimestampDesc(Long userId, Pageable pageable);

}