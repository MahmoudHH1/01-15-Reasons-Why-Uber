package com.team01.uber.ride;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.openfeign.EnableFeignClients;
import com.team01.uber.contracts.feign.DriverServiceClient;
import com.team01.uber.contracts.feign.LocationServiceClient;
import com.team01.uber.contracts.feign.UserServiceClient;

@SpringBootApplication
@EnableCaching
@EnableFeignClients(clients = {DriverServiceClient.class, LocationServiceClient.class, UserServiceClient.class})

public class RideServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RideServiceApplication.class, args);
    }

}
