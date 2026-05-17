package com.team01.uber.location.security;

public class LocationJwtConfigManager {

    private static volatile LocationJwtConfigManager instance;

    private final String secret;
    private final long expirationMs;

    private LocationJwtConfigManager() {
        String envSecret = System.getenv("JWT_SECRET");
        String envExpiration = System.getenv("JWT_EXPIRATION_MS");

        this.secret = (envSecret != null && !envSecret.isBlank())
                ? envSecret
                : "bXlzdXBlcnNlY3JldGtleWZvcmp3dGF1dGhlbnRpY2F0aW9u";

        this.expirationMs = (envExpiration != null && !envExpiration.isBlank())
                ? Long.parseLong(envExpiration)
                : 86400000L;
    }

    public static LocationJwtConfigManager getInstance() {
        if (instance == null) {
            synchronized (LocationJwtConfigManager.class) {
                if (instance == null) {
                    instance = new LocationJwtConfigManager();
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
