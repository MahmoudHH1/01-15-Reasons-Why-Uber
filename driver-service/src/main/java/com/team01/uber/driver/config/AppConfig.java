package com.team01.uber.driver.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class AppConfig {

    @Value("${ride.service.url}")
    private String rideServiceUrl;

    @Bean
    public RestClient rideServiceClient() {
        return RestClient.builder()
                .baseUrl(rideServiceUrl)
                .build();
    }
}
