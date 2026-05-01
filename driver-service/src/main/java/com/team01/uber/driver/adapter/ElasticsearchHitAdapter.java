package com.team01.uber.driver.adapter;

import com.team01.uber.driver.dto.DriverSearchResultDTO;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ElasticsearchHitAdapter {

    public DriverSearchResultDTO adapt(SearchHit<Map<String, Object>> hit) {
        Map<String, Object> source = hit.getContent();

        Long id = source.get("id") != null ? ((Number) source.get("id")).longValue() : null;
        Double rating = source.get("rating") != null ? ((Number) source.get("rating")).doubleValue() : null;

        return DriverSearchResultDTO.builder()
                .id(id)
                .name((String) source.get("name"))
                .vehicleType((String) source.get("vehicleType"))
                .description((String) source.get("description"))
                .rating(rating)
                .status((String) source.get("status"))
                .build();
    }
}
