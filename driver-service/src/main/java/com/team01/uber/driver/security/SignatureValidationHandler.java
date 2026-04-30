package com.team01.uber.driver.security;

public class SignatureValidationHandler extends AuthHandler {

    private final JwtService jwtService;

    public SignatureValidationHandler(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public void handle(AuthContext ctx) {
        if (!jwtService.isTokenValid(ctx.getToken())) {
            ctx.setErrorStatus(401);
            ctx.setErrorMessage("Invalid or expired token");
            return;
        }
        ctx.setEmail(jwtService.extractEmail(ctx.getToken()));
        ctx.setUid(jwtService.extractUserId(ctx.getToken()));
        ctx.setRole(jwtService.extractRole(ctx.getToken()));
        passToNext(ctx);
    }
}
