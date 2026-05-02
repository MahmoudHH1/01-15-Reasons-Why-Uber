package com.team01.uber.driver.repository;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.json.JsonData;
import com.team01.uber.driver.model.DriverSearchDocument;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public class DriverSearchEsRepository {
    private static final Logger log = LoggerFactory.getLogger(DriverSearchEsRepository.class);
    private final ElasticsearchOperations operations;

    public DriverSearchEsRepository(ElasticsearchOperations operations) {
        this.operations = operations;
    }

    @PostConstruct
    void initIndex() {
        ensureIndexExists();
    }

    public void ensureIndexExists() {
        try {
            IndexOperations indexOps = operations.indexOps(DriverSearchDocument.class);
            if (!indexOps.exists()) {
                indexOps.createWithMapping();
            }
        } catch (Exception e) {
            log.warn("Failed to ensure 'drivers' ES index mapping: {}", e.getMessage());
        }
    }

    public SearchHits<DriverSearchDocument> searchFullText(String query,
                                                           String vehicleType,
                                                           String status,
                                                           Double minRating,
                                                           Double maxRating) {
        String safeQuery = query == null ? "" : query;
        Query mustQuery = Query.of(q -> q.multiMatch(m -> m
                .query(safeQuery)
                .fields(List.of("name", "description"))
                .fuzziness("AUTO")));

        BoolQuery.Builder boolBuilder = new BoolQuery.Builder().must(mustQuery);

        if (vehicleType != null && !vehicleType.isBlank()) {
            String vt = vehicleType;
            boolBuilder.filter(Query.of(q -> q.term(t -> t.field("vehicleType").value(vt))));
        }
        if (status != null && !status.isBlank()) {
            String st = status;
            boolBuilder.filter(Query.of(q -> q.term(t -> t.field("status").value(st))));
        }
        if (minRating != null || maxRating != null) {
            boolBuilder.filter(Query.of(q -> q.range(r -> r.untyped(u -> {
                u.field("rating");
                if (minRating != null) u.gte(JsonData.of(minRating));
                if (maxRating != null) u.lte(JsonData.of(maxRating));
                return u;
            }))));
        }

        NativeQuery nq = NativeQuery.builder()
                .withQuery(Query.of(q -> q.bool(boolBuilder.build())))
                .build();

        return operations.search(nq, DriverSearchDocument.class);
    }
}