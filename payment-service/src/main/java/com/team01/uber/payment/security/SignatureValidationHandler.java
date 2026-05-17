package com.team01.uber.payment.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public class SignatureValidationHandler extends AuthHandler {

    private final JwtService jwtService;

    public SignatureValidationHandler(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean handle(AuthContext ctx) {
        if (!jwtService.isTokenValid(ctx.getToken())) {
            try {
                ctx.getResponse().setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                ctx.getResponse().getWriter().write("Invalid or expired token");
            } catch (IOException e) {
                ctx.getResponse().setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            }
            return false;
        }

        ctx.setEmail(jwtService.extractEmail(ctx.getToken()));
        ctx.setRole(jwtService.extractRole(ctx.getToken()));
        ctx.setUserId(jwtService.extractUserId(ctx.getToken()));
        return handleNext(ctx);
    }
}
