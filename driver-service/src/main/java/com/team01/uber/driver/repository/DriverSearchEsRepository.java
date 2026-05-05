package com.team01.uber.driver.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team01.uber.driver.model.DriverSearchDocument;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class DriverSearchEsRepository {
    private static final Logger log = LoggerFactory.getLogger(DriverSearchEsRepository.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String esBaseUri;

    public DriverSearchEsRepository(
            @Value("${spring.elasticsearch.uris:http://elasticsearch:9200}") String esBaseUri) {
        this.esBaseUri = esBaseUri;
    }

    @PostConstruct
    void initIndex() {
        ensureIndexExists();
    }

    public void ensureIndexExists() {
        try {
            String mapping = """
                    {
                      "mappings": {
                        "properties": {
                          "id":          {"type": "keyword"},
                          "name":        {"type": "text"},
                          "vehicleType": {"type": "keyword"},
                          "description": {"type": "text"},
                          "rating":      {"type": "double"},
                          "status":      {"type": "keyword"}
                        }
                      }
                    }
                    """;
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(esBaseUri + "/drivers"))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(mapping))
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            // 200 = created, 400 resource_already_exists = already exists — both are fine
            if (resp.statusCode() / 100 != 2 && resp.statusCode() != 400) {
                log.warn("Failed to ensure 'drivers' ES index mapping: status {}", resp.statusCode());
            }
        } catch (Exception e) {
            log.warn("Failed to ensure 'drivers' ES index mapping: {}", e.getMessage());
        }
    }

    public List<DriverSearchDocument> searchFullText(String query,
                                                     String vehicleType,
                                                     String status,
                                                     Double minRating,
                                                     Double maxRating) {
        try {
            Map<String, Object> mustClause;
            if (query == null || query.isBlank()) {
                mustClause = Map.of("match_all", Map.of());
            } else {
                mustClause = Map.of("multi_match", Map.of(
                        "query", query,
                        "fields", List.of("name^2", "description"),
                        "fuzziness", "AUTO"
                ));
            }

            List<Map<String, Object>> filters = new ArrayList<>();
            if (vehicleType != null && !vehicleType.isBlank()) {
                filters.add(Map.of("term", Map.of("vehicleType", vehicleType)));
            }
            if (status != null && !status.isBlank()) {
                filters.add(Map.of("term", Map.of("status", status.toUpperCase())));
            }
            if (minRating != null || maxRating != null) {
                Map<String, Object> range = new HashMap<>();
                if (minRating != null) range.put("gte", minRating);
                if (maxRating != null) range.put("lte", maxRating);
                filters.add(Map.of("range", Map.of("rating", range)));
            }

            Map<String, Object> boolQuery = new HashMap<>();
            boolQuery.put("must", List.of(mustClause));
            if (!filters.isEmpty()) {
                boolQuery.put("filter", filters);
            }

            Map<String, Object> requestBody = Map.of("query", Map.of("bool", boolQuery));
            String body = objectMapper.writeValueAsString(requestBody);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(esBaseUri + "/drivers/_search"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("ES search returned {}: {}", resp.statusCode(), resp.body());
                return List.of();
            }

            return parseHits(resp.body());
        } catch (Exception e) {
            log.warn("ES search failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<DriverSearchDocument> parseHits(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode hitsArray = root.path("hits").path("hits");
        List<DriverSearchDocument> results = new ArrayList<>();
        for (JsonNode hit : hitsArray) {
            JsonNode src = hit.path("_source");
            DriverSearchDocument doc = DriverSearchDocument.builder()
                    .id(src.has("id") ? src.get("id").asLong() : null)
                    .name(src.has("name") ? src.get("name").asText(null) : null)
                    .vehicleType(src.has("vehicleType") ? src.get("vehicleType").asText(null) : null)
                    .description(src.has("description") ? src.get("description").asText(null) : null)
                    .rating(src.has("rating") ? src.get("rating").asDouble() : null)
                    .status(src.has("status") ? src.get("status").asText(null) : null)
                    .build();
            results.add(doc);
        }
        return results;
    }
}
