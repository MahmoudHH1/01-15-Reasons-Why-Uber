package com.team01.uber.tests.fixtures;

import com.team01.uber.contracts.security.JwtConfigurationManager;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;

public final class JwtTestHelper {

    private static final SecretKey SIGNING_KEY = Keys.hmacShaKeyFor(
            Base64.getDecoder().decode(JwtConfigurationManager.getInstance().getSecret()));

    private JwtTestHelper() {}

    public static String tokenFor(long uid, String email, String role) {
        return tokenFor(uid, email, role, JwtConfigurationManager.getInstance().getExpirationMs());
    }

    public static String tokenFor(long uid, String email, String role, long expirationMs) {
        Date now = new Date();
        return Jwts.builder()
                .subject(email)
                .claim("uid", uid)
                .claim("role", role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationMs))
                .signWith(SIGNING_KEY)
                .compact();
    }

    public static String expiredToken(long uid, String email, String role) {
        Date past = new Date(System.currentTimeMillis() - 10_000);
        return Jwts.builder()
                .subject(email)
                .claim("uid", uid)
                .claim("role", role)
                .issuedAt(new Date(past.getTime() - 60_000))
                .expiration(past)
                .signWith(SIGNING_KEY)
                .compact();
    }

    public static String malformedToken() {
        return "not.a.valid.jwt";
    }
}
