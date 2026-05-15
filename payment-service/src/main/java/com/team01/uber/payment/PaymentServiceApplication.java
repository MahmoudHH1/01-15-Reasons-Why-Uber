package com.team01.uber.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

import com.team01.uber.contracts.feign.DriverServiceClient;
import com.team01.uber.contracts.feign.RideServiceClient;
import com.team01.uber.contracts.feign.UserServiceClient;

import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableCaching
@EnableFeignClients(clients = {UserServiceClient.class, RideServiceClient.class, DriverServiceClient.class})

public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
