package com.team01.uber.tests.fixtures;

import java.util.UUID;

public final class GatewayHeaders {

    public static final String X_USER_ID = "X-User-Id";
    public static final String X_USER_ROLE = "X-User-Role";
    public static final String X_CORRELATION_ID = "X-Correlation-ID";

    private GatewayHeaders() {}

    public static Http.Builder injectInto(Http.Builder request, long uid, String role) {
        return request
                .header(X_USER_ID, String.valueOf(uid))
                .header(X_USER_ROLE, role)
                .header(X_CORRELATION_ID, UUID.randomUUID().toString());
    }

    public static Http.Builder withBearer(Http.Builder request, String token) {
        return request
                .bearer(token)
                .header(X_CORRELATION_ID, UUID.randomUUID().toString());
    }
}
