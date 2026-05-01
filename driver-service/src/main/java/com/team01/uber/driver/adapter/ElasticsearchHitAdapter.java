package com.team01.uber.driver.adapter;

import com.team01.uber.driver.dto.DriverSearchResultDTO;
import com.team01.uber.driver.model.DriverSearchDocument;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.stereotype.Component;

@Component
public class ElasticsearchHitAdapter {

    public DriverSearchResultDTO adapt(SearchHit<DriverSearchDocument> hit) {
        DriverSearchDocument src = hit.getContent();
        return DriverSearchResultDTO.builder()
                .id(src.getId())
                .name(src.getName())
                .vehicleType(src.getVehicleType())
                .description(src.getDescription())
                .rating(src.getRating())
                .status(src.getStatus())
                .build();
    }
}
