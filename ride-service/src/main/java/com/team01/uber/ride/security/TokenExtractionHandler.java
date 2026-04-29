package com.team01.uber.ride.security;

import jakarta.servlet.http.HttpServletResponse;

public class TokenExtractionHandler extends AuthHandler {
    @Override
    protected boolean process(AuthContext ctx) throws Exception {
        String authHeader = ctx.getRequest().getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            ctx.getResponse().setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            ctx.getResponse().getWriter().write("Missing or malformed Authorization header");
            return false;
        }
        ctx.setToken(authHeader.substring(7));
        return true;
    }
}