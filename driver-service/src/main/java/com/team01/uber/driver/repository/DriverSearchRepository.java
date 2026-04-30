package com.team01.uber.driver.repository;

import com.team01.uber.driver.model.DriverSearchDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface DriverSearchRepository extends ElasticsearchRepository<DriverSearchDocument, String> {
}
