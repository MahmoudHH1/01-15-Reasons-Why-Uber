package com.team01.uber.ride.security;

import com.team01.uber.ride.client.UserClient;
import feign.FeignException;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UserLoaderHandler extends AuthHandler {
    private static final Logger log = LoggerFactory.getLogger(UserLoaderHandler.class);
    private final UserClient userClient;

    public UserLoaderHandler(UserClient userClient) {
        this.userClient = userClient;
    }

    @Override
    protected boolean process(AuthContext ctx) throws Exception {
        if (ctx.getUserId() == null) {
            ctx.getResponse().setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            ctx.getResponse().getWriter().write("Missing uid claim");
            return false;
        }

        log.info("Calling {}.{} with args={}", "UserClient", "getUser", ctx.getUserId());
        try {
            userClient.getUser(ctx.getUserId());
            log.info("{}.{} returned successfully", "UserClient", "getUser");
            return true;
        } catch (FeignException.NotFound e) {
            log.warn("Resilient call to {} failed: {}", "user-service", e.getMessage());
            ctx.getResponse().setStatus(HttpServletResponse.SC_NOT_FOUND);
            ctx.getResponse().getWriter().write("caller user not found");
            return false;
        } catch (Exception e) {
            log.warn("Resilient call to {} failed with error: {}", "user-service", e.getMessage());
            // Fallback handled by client, allowing to proceed if not 404
            return true;
        }
    }
}
