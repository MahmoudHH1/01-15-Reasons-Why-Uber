package com.team01.uber.payment.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public class TokenExtractionHandler extends AuthHandler {

    @Override
    public boolean handle(AuthContext ctx) {
        String authHeader = ctx.getRequest().getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            try {
                ctx.getResponse().setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                ctx.getResponse().getWriter().write("Missing or malformed Authorization header");
            } catch (IOException e) {
                ctx.getResponse().setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            }
            return false;
        }

        ctx.setToken(authHeader.substring(7));
        return handleNext(ctx);
    }
}
