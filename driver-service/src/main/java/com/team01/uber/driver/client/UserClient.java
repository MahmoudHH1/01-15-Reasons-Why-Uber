package com.team01.uber.driver.client;

import com.team01.uber.contracts.dto.UserDTO;
import com.team01.uber.contracts.feign.UserServiceClient;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@Slf4j
public class UserClient {

    private final UserServiceClient feignClient;

    public UserClient(UserServiceClient feignClient) {
        this.feignClient = feignClient;
    }

    @CircuitBreaker(name = "user-service", fallbackMethod = "getUserFallback")
    public UserDTO getUser(Long userId) {
        try {
            log.info("Calling user-service.getUser for userId={}", userId);
            return feignClient.getUser(userId);
        } catch (FeignException.NotFound e) {
            log.warn("User not found for userId={}", userId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        } catch (FeignException e) {
            log.error("Feign call to user-service failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "User service unavailable");
        }
    }

    public UserDTO getUserFallback(Long userId, Exception e) {
        log.warn("Circuit breaker open — fallback for user-service.getUser userId={}: {}", userId, e.getMessage());
        return new UserDTO(userId, "Unknown", "unknown@example.com", "USER", "ACTIVE");
    }
}
