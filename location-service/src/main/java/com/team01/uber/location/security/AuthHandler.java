package com.team01.uber.location.security;

public abstract class AuthHandler {
    private AuthHandler next;

    public AuthHandler setNext(AuthHandler next) {
        this.next = next;
        return next;
    }

    public boolean handle(AuthContext ctx) throws Exception {
        if (process(ctx)) {
            if (next != null) {
                return next.handle(ctx);
            }
            return true;
        }
        return false;
    }

    protected abstract boolean process(AuthContext ctx) throws Exception;
}
