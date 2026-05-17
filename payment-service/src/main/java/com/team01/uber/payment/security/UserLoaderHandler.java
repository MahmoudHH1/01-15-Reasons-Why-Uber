package com.team01.uber.payment.security;

import com.team01.uber.payment.client.UserClient;
import feign.FeignException;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class UserLoaderHandler extends AuthHandler {
    private static final Logger log = LoggerFactory.getLogger(UserLoaderHandler.class);
    private final UserClient userClient;

    public UserLoaderHandler(UserClient userClient) {
        this.userClient = userClient;
    }

    @Override
    public boolean handle(AuthContext ctx) {
        if (ctx.getUserId() == null) {
            writeStatus(ctx, HttpServletResponse.SC_UNAUTHORIZED, "Missing uid claim");
            return false;
        }

        log.info("Calling {}.{} with args={}", "UserClient", "getUser", ctx.getUserId());
        try {
            userClient.getUser(ctx.getUserId());
            log.info("{}.{} returned successfully", "UserClient", "getUser");
        } catch (FeignException.NotFound e) {
            log.warn("Resilient call to {} failed: {}", "user-service", e.getMessage());
            writeStatus(ctx, HttpServletResponse.SC_NOT_FOUND, "caller user not found");
            return false;
        } catch (Exception e) {
            log.warn("Resilient call to {} failed with error: {}", "user-service", e.getMessage());
            // Fallback handled by client, allowing to proceed if not 404
        }
        return handleNext(ctx);
    }

    private static void writeStatus(AuthContext ctx, int status, String body) {
        try {
            ctx.getResponse().setStatus(status);
            ctx.getResponse().getWriter().write(body);
        } catch (IOException ignored) {
            ctx.getResponse().setStatus(status);
        }
    }
}
