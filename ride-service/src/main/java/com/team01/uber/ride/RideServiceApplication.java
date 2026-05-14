package com.team01.uber.ride;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableCaching
@EnableFeignClients(clients = {
    com.team01.uber.contracts.feign.DriverServiceClient.class,
    com.team01.uber.contracts.feign.LocationServiceClient.class,
    com.team01.uber.contracts.feign.UserServiceClient.class
})
public class RideServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RideServiceApplication.class, args);
    }

}
