package com.team01.uber.location.security;

import jakarta.servlet.http.HttpServletResponse;

public class TokenExtractionHandler extends AuthHandler {
    @Override
    protected boolean process(AuthContext ctx) throws Exception {
        String authHeader = ctx.getRequest().getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            // Do not set 401 here, just return false to let the next handler or Spring Security handle it
            return false;
        }
        ctx.setToken(authHeader.substring(7));
        return true;
    }
}
