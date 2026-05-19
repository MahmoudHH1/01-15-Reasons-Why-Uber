package com.team01.uber.ride.client;

import com.team01.uber.contracts.dto.UserDTO;
import com.team01.uber.contracts.feign.UserServiceClient;
import feign.FeignException;
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

    public UserDTO getUser(Long userId) {
        try {
            log.info("Calling user-service.getUser for userId={}", userId);
            return feignClient.getUser(userId);
        } catch (FeignException.NotFound e) {
            log.warn("User not found for userId={}", userId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        } catch (FeignException e) {
            log.warn("user-service unavailable for getUser userId={}: {}", userId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "User service unavailable");
        }
    }
}
