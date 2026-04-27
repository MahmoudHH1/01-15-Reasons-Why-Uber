package com.team01.uber.location.security;

import jakarta.servlet.http.HttpServletResponse;

public class SignatureValidationHandler extends AuthHandler {
    private final JwtService jwtService;

    public SignatureValidationHandler(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected boolean process(AuthContext ctx) throws Exception {
        if (!jwtService.isTokenValid(ctx.getToken())) {
            ctx.getResponse().setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            ctx.getResponse().getWriter().write("Invalid or expired token");
            return false;
        }
        ctx.setEmail(jwtService.extractEmail(ctx.getToken()));
        ctx.setRole(jwtService.extractRole(ctx.getToken()));
        ctx.setUserId(jwtService.extractUserId(ctx.getToken()));
        return true;
    }
}
