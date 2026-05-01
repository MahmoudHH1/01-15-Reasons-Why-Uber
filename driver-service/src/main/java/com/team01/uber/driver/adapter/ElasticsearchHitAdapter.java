package com.team01.uber.driver.adapter;

import com.team01.uber.driver.dto.DriverSearchResultDTO;
import com.team01.uber.driver.model.DriverSearchDocument;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.stereotype.Component;

@Component
public class ElasticsearchHitAdapter {

    public DriverSearchResultDTO adapt(SearchHit<DriverSearchDocument> hit) {
        DriverSearchDocument source = hit.getContent();

        return DriverSearchResultDTO.builder()
                .id(source.getId())
                .name(source.getName())
                .vehicleType(source.getVehicleType())
                .description(source.getDescription())
                .rating(source.getRating())
                .status(source.getStatus())
                .build();
    }
}
