package com.team01.uber.driver.security;

public class TokenExtractionHandler extends AuthHandler {

    @Override
    public void handle(AuthContext ctx) {
        String header = ctx.getRequest().getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            ctx.setErrorStatus(401);
            ctx.setErrorMessage("Missing or malformed Authorization header");
            return;
        }
        ctx.setToken(header.substring(7));
        passToNext(ctx);
    }
}
