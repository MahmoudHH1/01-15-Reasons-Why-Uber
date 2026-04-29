package com.team01.uber.user.security.chain;

import org.springframework.security.core.AuthenticationException;

public class TokenExtractionHandler extends AuthHandler {

    @Override
    public void handle(AuthContext ctx) throws AuthenticationException {
        String header = ctx.getRequest().getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new AuthenticationException("Missing or malformed Authorization header") {};
        }
        ctx.setToken(header.substring(7));
        handleNext(ctx);
    }
}