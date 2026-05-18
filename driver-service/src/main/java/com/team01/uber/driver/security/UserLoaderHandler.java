package com.team01.uber.driver.security;

import com.team01.uber.driver.client.UserClient;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UserLoaderHandler extends AuthHandler {
    private static final Logger log = LoggerFactory.getLogger(UserLoaderHandler.class);
    private final UserClient userClient;

    public UserLoaderHandler(UserClient userClient) {
        this.userClient = userClient;
    }

    @Override
    public void handle(AuthContext ctx) {
        if (ctx.getUid() == null) {
            ctx.setErrorStatus(401);
            ctx.setErrorMessage("Missing uid claim");
            return;
        }

        log.info("Calling {}.{} with args={}", "UserClient", "getUser", ctx.getUid());
        try {
            userClient.getUser(ctx.getUid());
            log.info("{}.{} returned successfully", "UserClient", "getUser");
        } catch (FeignException.NotFound e) {
            log.warn("Feign call to {} failed: {}", "user-service", e.getMessage());
            ctx.setErrorStatus(404);
            ctx.setErrorMessage("caller user not found");
            return;
        } catch (Exception e) {
            log.warn("Resilient call to {} failed: {}", "user-service", e.getMessage());
            // If it's not a 404, the circuit breaker/fallback might have returned a placeholder.
            // In a real system, we might want to decide if we trust the fallback for security.
            // For now, we allow it to proceed if the client didn't throw a fatal exception.
        }
        passToNext(ctx);
    }
}
