package com.team01.uber.tests.fixtures;

import com.team01.uber.contracts.security.JwtConfigurationManager;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.util.Base64;

public final class JwtClaims {

    private static final SecretKey SIGNING_KEY = Keys.hmacShaKeyFor(
            Base64.getDecoder().decode(JwtConfigurationManager.getInstance().getSecret()));

    private JwtClaims() {}

    public static Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(SIGNING_KEY)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public static long uidOf(String token) {
        return parse(token).get("uid", Number.class).longValue();
    }

    public static String roleOf(String token) {
        return parse(token).get("role", String.class);
    }

    public static String emailOf(String token) {
        return parse(token).getSubject();
    }
}
