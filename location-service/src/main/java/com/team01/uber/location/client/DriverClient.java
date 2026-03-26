package com.team01.uber.location.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class DriverClient implements DriverLookupService {

    private final RestClient restClient;

    public DriverClient(@Value("${driver.service.base-url}") String driverServiceBaseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(driverServiceBaseUrl)
                .build();
    }

    @Override
    public boolean existsById(Long driverId) {
        try {
            restClient.get()
                    .uri("/api/drivers/{id}", driverId)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException ex) {
            HttpStatusCode status = ex.getStatusCode();
            if (status.value() == 404) {
                return false;
            }
            throw ex;
        }
    }
}
