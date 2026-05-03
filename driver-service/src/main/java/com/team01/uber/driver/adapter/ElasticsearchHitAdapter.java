package com.team01.uber.driver.adapter;

import com.team01.uber.driver.dto.DriverSearchResultDTO;
import com.team01.uber.driver.model.DriverSearchDocument;
import org.springframework.stereotype.Component;

@Component
public class ElasticsearchHitAdapter {
    public DriverSearchResultDTO adapt(DriverSearchDocument doc) {
        return DriverSearchResultDTO.builder()
                .id(doc.getId())
                .name(doc.getName())
                .vehicleType(doc.getVehicleType())
                .description(doc.getDescription())
                .rating(doc.getRating())
                .status(doc.getStatus())
                .build();
    }
}
