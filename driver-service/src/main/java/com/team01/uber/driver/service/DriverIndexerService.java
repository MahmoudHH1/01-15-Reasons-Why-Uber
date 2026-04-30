package com.team01.uber.driver.service;

import com.team01.uber.driver.adapter.ElasticsearchHitAdapter;
import com.team01.uber.driver.model.Driver;
import com.team01.uber.driver.model.DriverSearchDocument;
import com.team01.uber.driver.observer.EntityObserver;
import com.team01.uber.driver.repository.DriverSearchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DriverIndexerService {

    private static final Logger log = LoggerFactory.getLogger(DriverIndexerService.class);

    static final String SOURCE_EXPLICIT = "explicit";
    static final String SOURCE_AUTO_CREATE = "auto_crud_create";
    static final String SOURCE_AUTO_UPDATE = "auto_crud_update";

    private static final List<String> INDEXED_FIELDS = List.of(
            "id", "name", "vehicleType", "description", "rating", "status"
    );

    private final DriverSearchRepository searchRepository;
    private final ElasticsearchHitAdapter adapter;
    private final CacheInvalidationService cacheInvalidationService;

    private final List<EntityObserver> observers = new ArrayList<>();

    public DriverIndexerService(DriverSearchRepository searchRepository,
                                ElasticsearchHitAdapter adapter,
                                CacheInvalidationService cacheInvalidationService) {
        this.searchRepository = searchRepository;
        this.adapter = adapter;
        this.cacheInvalidationService = cacheInvalidationService;
    }

    public void register(EntityObserver observer) {
        observers.add(observer);
    }

    public void unregister(EntityObserver observer) {
        observers.remove(observer);
    }

    private void notifyObservers(String action, Object payload) {
        for (EntityObserver observer : observers) {
            observer.onEvent(action, payload);
        }
    }

    public void index(Driver driver, String source) {
        if (driver == null || driver.getId() == null) {
            return;
        }

        DriverSearchDocument document = adapter.toDocument(driver);

        try {
            searchRepository.save(document);
        } catch (Exception e) {
            log.warn("Failed to index driver {} to Elasticsearch: {}", driver.getId(), e.getMessage());
        }

        Map<String, Object> details = new HashMap<>();
        details.put("driverId", driver.getId());
        details.put("indexedFields", INDEXED_FIELDS);
        details.put("source", source);

        Map<String, Object> payload = new HashMap<>();
        payload.put("driverId", driver.getId());
        payload.put("details", details);

        notifyObservers("INDEXED", payload);
        cacheInvalidationService.invalidateDriverIndexCaches(driver.getId());
    }

    public void removeFromIndex(Long driverId) {
        if (driverId == null) {
            return;
        }

        String docId = String.valueOf(driverId);
        try {
            searchRepository.deleteById(docId);
        } catch (Exception e) {
            log.warn("Failed to remove driver {} from Elasticsearch: {}", driverId, e.getMessage());
        }

        Map<String, Object> details = new HashMap<>();
        details.put("driverId", driverId);

        Map<String, Object> payload = new HashMap<>();
        payload.put("driverId", driverId);
        payload.put("details", details);

        notifyObservers("DRIVER_DELETED", payload);
        cacheInvalidationService.invalidateDriverIndexCaches(driverId);
    }
}
