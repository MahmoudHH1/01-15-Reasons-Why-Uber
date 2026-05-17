package com.team01.uber.driver.security;

import com.team01.uber.contracts.feign.UserServiceClient;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UserLoaderHandler extends AuthHandler {
    private static final Logger log = LoggerFactory.getLogger(UserLoaderHandler.class);
    private final UserServiceClient userServiceClient;

    public UserLoaderHandler(UserServiceClient userServiceClient) {
        this.userServiceClient = userServiceClient;
    }

    @Override
    public void handle(AuthContext ctx) {
        if (ctx.getUid() == null) {
            ctx.setErrorStatus(401);
            ctx.setErrorMessage("Missing uid claim");
            return;
        }

        log.info("Calling {}.{} with args={}", "UserServiceClient", "getUser", ctx.getUid());
        try {
            userServiceClient.getUser(ctx.getUid());
            log.info("{}.{} returned successfully", "UserServiceClient", "getUser");
        } catch (FeignException.NotFound e) {
            log.warn("Feign call to {} failed: {}", "user-service", e.getMessage());
            ctx.setErrorStatus(404);
            ctx.setErrorMessage("caller user not found");
            return;
        } catch (FeignException e) {
            log.warn("Feign call to {} failed: {}", "user-service", e.getMessage());
            ctx.setErrorStatus(503);
            ctx.setErrorMessage("User service temporarily unavailable");
            return;
        }
        passToNext(ctx);
    }
}
