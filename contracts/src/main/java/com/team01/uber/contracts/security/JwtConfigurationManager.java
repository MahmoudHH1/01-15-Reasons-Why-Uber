package com.team01.uber.contracts.security;

public class JwtConfigurationManager {

    private static volatile JwtConfigurationManager instance;

    private final String secret;
    private final long expirationMs;

    private JwtConfigurationManager() {
        String envSecret = System.getenv("JWT_SECRET");
        String envExpiration = System.getenv("JWT_EXPIRATION_MS");

        this.secret = (envSecret != null && !envSecret.isBlank())
                ? envSecret
                : "bXlzdXBlcnNlY3JldGtleWZvcmp3dGF1dGhlbnRpY2F0aW9u";

        this.expirationMs = (envExpiration != null && !envExpiration.isBlank())
                ? Long.parseLong(envExpiration)
                : 86400000L;
    }

    public static JwtConfigurationManager getInstance() {
        if (instance == null) {
            synchronized (JwtConfigurationManager.class) {
                if (instance == null) {
                    instance = new JwtConfigurationManager();
                }
            }
        }
        return instance;
    }

    public String getSecret() {
        return secret;
    }

    public long getExpirationMs() {
        return expirationMs;
    }
}
