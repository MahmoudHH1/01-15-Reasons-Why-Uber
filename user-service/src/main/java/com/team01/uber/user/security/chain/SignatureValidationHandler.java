package com.team01.uber.user.security.chain;

import com.team01.uber.user.security.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.security.core.AuthenticationException;

public class SignatureValidationHandler extends AuthHandler {

    private final JwtService jwtService;

    public SignatureValidationHandler(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public void handle(AuthContext ctx) throws AuthenticationException {
        try {
            Claims claims = jwtService.extractAllClaims(ctx.getToken());
            ctx.setClaims(claims);
        } catch (JwtException e) {
            throw new AuthenticationException("Invalid or expired JWT: " + e.getMessage()) {};
        }
        handleNext(ctx);
    }
}