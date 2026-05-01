package com.team01.uber.user.security.chain;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;

public abstract class AuthHandler {

    protected AuthHandler next;

    public AuthHandler setNext(AuthHandler next) {
        this.next = next;
        return next;
    }

    public abstract void handle(AuthContext ctx)
            throws AuthenticationException, AccessDeniedException;

    protected void handleNext(AuthContext ctx)
            throws AuthenticationException, AccessDeniedException {
        if (next != null) next.handle(ctx);
    }
}