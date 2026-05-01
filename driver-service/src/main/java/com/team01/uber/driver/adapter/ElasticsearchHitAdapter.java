package com.team01.uber.driver.adapter;

import com.team01.uber.driver.dto.DriverSearchResultDTO;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ElasticsearchHitAdapter {

    public DriverSearchResultDTO adapt(SearchHit<Map<String, Object>> hit) {
        Map<String, Object> source = hit.getContent();

        return DriverSearchResultDTO.builder()
                .id(toLong(source.get("id")))
                .name((String) source.get("name"))
                .vehicleType((String) source.get("vehicleType"))
                .description((String) source.get("description"))
                .rating(toDouble(source.get("rating")))
                .status((String) source.get("status"))
                .build();
    }

    private static Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(v.toString());
    }

    private static Double toDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        return Double.parseDouble(v.toString());
    }
}
