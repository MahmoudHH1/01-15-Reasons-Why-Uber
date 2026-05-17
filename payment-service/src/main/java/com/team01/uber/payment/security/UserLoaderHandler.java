package com.team01.uber.payment.security;

import com.team01.uber.contracts.feign.UserServiceClient;
import feign.FeignException;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public class UserLoaderHandler extends AuthHandler {

    private final UserServiceClient userServiceClient;

    public UserLoaderHandler(UserServiceClient userServiceClient) {
        this.userServiceClient = userServiceClient;
    }

    @Override
    public boolean handle(AuthContext ctx) {
        if (ctx.getUserId() == null) {
            writeStatus(ctx, HttpServletResponse.SC_UNAUTHORIZED, "Missing uid claim");
            return false;
        }

        try {
            userServiceClient.getUser(ctx.getUserId());
            return handleNext(ctx);
        } catch (FeignException.NotFound e) {
            writeStatus(ctx, HttpServletResponse.SC_NOT_FOUND, "caller user not found");
            return false;
        } catch (FeignException e) {
            writeStatus(ctx, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "User service temporarily unavailable");
            return false;
        }
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
