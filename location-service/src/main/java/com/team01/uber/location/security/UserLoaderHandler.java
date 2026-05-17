package com.team01.uber.location.security;

import com.team01.uber.contracts.feign.UserServiceClient;
import feign.FeignException;
import jakarta.servlet.http.HttpServletResponse;

public class UserLoaderHandler extends AuthHandler {
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

        try {
            userServiceClient.getUser(ctx.getUserId());
            return true;
        } catch (FeignException.NotFound e) {
            ctx.getResponse().setStatus(HttpServletResponse.SC_NOT_FOUND);
            ctx.getResponse().getWriter().write("caller user not found");
            return false;
        } catch (FeignException e) {
            ctx.getResponse().setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            ctx.getResponse().getWriter().write("User service temporarily unavailable");
            return false;
        }
    }
}
