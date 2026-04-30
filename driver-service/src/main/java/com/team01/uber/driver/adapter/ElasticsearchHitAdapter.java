package com.team01.uber.driver.adapter;

import com.team01.uber.driver.model.Driver;
import com.team01.uber.driver.model.DriverSearchDocument;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ElasticsearchHitAdapter {

    public DriverSearchDocument toDocument(Driver driver) {
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

        return new DriverSearchDocument(
                String.valueOf(driver.getId()),
                driver.getName(),
                vehicleType,
                description,
                driver.getRating(),
                driver.getStatus() != null ? driver.getStatus().name() : null
        );
    }
}
