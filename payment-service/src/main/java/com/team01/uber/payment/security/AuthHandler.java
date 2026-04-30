package com.team01.uber.payment.security;

public abstract class AuthHandler {

    private AuthHandler next;

    public AuthHandler setNext(AuthHandler next) {
        this.next = next;
        return next;
    }

    public abstract boolean handle(AuthContext ctx);

    protected boolean handleNext(AuthContext ctx) {
        if (next == null) return true;
        return next.handle(ctx);
    }
}
