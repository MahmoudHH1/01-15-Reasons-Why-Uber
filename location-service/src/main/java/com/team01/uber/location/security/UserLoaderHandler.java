package com.team01.uber.location.security;

import com.team01.uber.contracts.feign.UserServiceClient;
import feign.FeignException;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UserLoaderHandler extends AuthHandler {
    private static final Logger log = LoggerFactory.getLogger(UserLoaderHandler.class);
    private final UserServiceClient userServiceClient;

    public UserLoaderHandler(UserServiceClient userServiceClient) {
        this.userServiceClient = userServiceClient;
    }

    @Override
    protected boolean process(AuthContext ctx) throws Exception {
        if (ctx.getUserId() == null) {
            ctx.getResponse().setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            ctx.getResponse().getWriter().write("Missing uid claim");
            return false;
        }

        log.info("Calling {}.{} with args={}", "UserServiceClient", "getUser", ctx.getUserId());
        try {
            userServiceClient.getUser(ctx.getUserId());
            log.info("{}.{} returned successfully", "UserServiceClient", "getUser");
            return true;
        } catch (FeignException.NotFound e) {
            log.warn("Feign call to {} failed: {}", "user-service", e.getMessage());
            ctx.getResponse().setStatus(HttpServletResponse.SC_NOT_FOUND);
            ctx.getResponse().getWriter().write("caller user not found");
            return false;
        } catch (FeignException e) {
            log.warn("Feign call to {} failed: {}", "user-service", e.getMessage());
            ctx.getResponse().setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            ctx.getResponse().getWriter().write("User service temporarily unavailable");
            return false;
        }
    }
}
