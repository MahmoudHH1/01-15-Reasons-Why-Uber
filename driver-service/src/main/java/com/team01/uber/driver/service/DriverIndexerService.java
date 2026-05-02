package com.team01.uber.driver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team01.uber.driver.cache.CacheInvalidator;
import com.team01.uber.driver.model.Driver;
import com.team01.uber.driver.observer.EntityObserver;
import com.team01.uber.driver.observer.MongoEventLogger;
import com.team01.uber.driver.repository.DriverSearchEsRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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

    private final CacheInvalidator cacheInvalidator;
    private final MongoEventLogger mongoEventLogger;
    private final DriverSearchEsRepository searchEsRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final String esBaseUri;

    private final List<EntityObserver> observers = new ArrayList<>();

    public DriverIndexerService(CacheInvalidator cacheInvalidator,
                                MongoEventLogger mongoEventLogger,
                                DriverSearchEsRepository searchEsRepository,
                                @Value("${spring.elasticsearch.uris:http://elasticsearch:9200}") String esBaseUri) {
        this.cacheInvalidator = cacheInvalidator;
        this.mongoEventLogger = mongoEventLogger;
        this.searchEsRepository = searchEsRepository;
        this.esBaseUri = esBaseUri;
    }

    @PostConstruct
    void init() {
        register(mongoEventLogger);
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

        searchEsRepository.ensureIndexExists();

        Map<String, Object> doc = toEsDocument(driver);

        try {
            String body = objectMapper.writeValueAsString(doc);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(esBaseUri + "/drivers/_doc/" + driver.getId() + "?refresh=wait_for"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("ES index returned {} for driver {}: {}", resp.statusCode(), driver.getId(), resp.body());
            }
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
        invalidateIndexCaches(driver.getId());
    }

    public void removeFromIndex(Long driverId) {
        if (driverId == null) {
            return;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(esBaseUri + "/drivers/_doc/" + driverId + "?refresh=wait_for"))
                    .timeout(Duration.ofSeconds(3))
                    .DELETE()
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2 && resp.statusCode() != 404) {
                log.warn("ES delete returned {} for driver {}: {}", resp.statusCode(), driverId, resp.body());
            }
        } catch (Exception e) {
            log.warn("Failed to remove driver {} from Elasticsearch: {}", driverId, e.getMessage());
        }

        invalidateIndexCaches(driverId);
    }

    private Map<String, Object> toEsDocument(Driver driver) {
        Map<String, Object> details = driver.getVehicleDetails();
        String description = "";
        String vehicleType = null;
        if (details != null) {
            Object rawDescription = details.get("description");
            if (rawDescription != null) {
                description = String.valueOf(rawDescription);
            }
            Object rawType = details.get("vehicleType");
            if (rawType != null) {
                vehicleType = String.valueOf(rawType);
            }
        }

        Map<String, Object> doc = new HashMap<>();
        doc.put("id", driver.getId());
        doc.put("name", driver.getName());
        doc.put("vehicleType", vehicleType);
        doc.put("description", description);
        doc.put("rating", driver.getRating());
        doc.put("status", driver.getStatus() != null ? driver.getStatus().name() : null);
        return doc;
    }

    private void invalidateIndexCaches(Long driverId) {
        cacheInvalidator.deleteByPattern("driver-service::S2-F10::*");
        cacheInvalidator.deleteEntity("driver", driverId);
        cacheInvalidator.deleteByPattern("driver-service::S2-F12::" + driverId);
    }
}
