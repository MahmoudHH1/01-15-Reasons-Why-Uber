package com.team01.uber.driver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team01.uber.driver.adapter.ElasticsearchHitAdapter;
import com.team01.uber.driver.model.Driver;
import com.team01.uber.driver.model.DriverSearchDocument;
import com.team01.uber.driver.observer.EntityObserver;
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

    private final ElasticsearchHitAdapter adapter;
    private final CacheInvalidationService cacheInvalidationService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final String esBaseUri;

    private final List<EntityObserver> observers = new ArrayList<>();

    public DriverIndexerService(ElasticsearchHitAdapter adapter,
                                CacheInvalidationService cacheInvalidationService,
                                @Value("${spring.elasticsearch.uris:http://elasticsearch:9200}") String esBaseUri) {
        this.adapter = adapter;
        this.cacheInvalidationService = cacheInvalidationService;
        this.esBaseUri = esBaseUri;
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
            String body = objectMapper.writeValueAsString(document);
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
        cacheInvalidationService.invalidateDriverIndexCaches(driver.getId());
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

        Map<String, Object> details = new HashMap<>();
        details.put("driverId", driverId);

        Map<String, Object> payload = new HashMap<>();
        payload.put("driverId", driverId);
        payload.put("details", details);

        notifyObservers("DRIVER_DELETED", payload);
        cacheInvalidationService.invalidateDriverIndexCaches(driverId);
    }
}
