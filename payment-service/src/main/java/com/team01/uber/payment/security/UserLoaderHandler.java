package com.team01.uber.payment.security;

public class UserLoaderHandler extends AuthHandler {

    @Override
    public boolean handle(AuthContext ctx) {
        // M3: payment-service has no users table — trust JWT claims validated by the gateway.
        return handleNext(ctx);
    }
}
