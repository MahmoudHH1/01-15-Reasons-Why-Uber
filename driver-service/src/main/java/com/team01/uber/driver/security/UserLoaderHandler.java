package com.team01.uber.driver.security;

import com.team01.uber.contracts.feign.UserServiceClient;
import feign.FeignException;

public class UserLoaderHandler extends AuthHandler {

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

        try {
            userServiceClient.getUser(ctx.getUid());
        } catch (FeignException.NotFound e) {
            ctx.setErrorStatus(404);
            ctx.setErrorMessage("caller user not found");
            return;
        } catch (FeignException e) {
            ctx.setErrorStatus(503);
            ctx.setErrorMessage("User service temporarily unavailable");
            return;
        }
        passToNext(ctx);
    }
}
