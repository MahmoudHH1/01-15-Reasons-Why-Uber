package com.team01.uber.location;

import com.team01.uber.contracts.feign.DriverServiceClient;
import com.team01.uber.contracts.feign.UserServiceClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableCaching
@EnableFeignClients(clients = {DriverServiceClient.class, UserServiceClient.class})
public class LocationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LocationServiceApplication.class, args);
    }

}
