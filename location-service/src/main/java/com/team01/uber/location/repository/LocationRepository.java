package com.team01.uber.location.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.team01.uber.location.model.Location;

import java.util.List;
import java.util.Optional;

@Repository
public interface LocationRepository extends JpaRepository<Location, Long> {
// Inherits from JpaRepository:
    // save(location)        → create / update
    // findById(id)          → read by ID
    // findAll()             → read all
    // deleteById(id)        → delete by ID
    // existsById(id)        → exists check

}
