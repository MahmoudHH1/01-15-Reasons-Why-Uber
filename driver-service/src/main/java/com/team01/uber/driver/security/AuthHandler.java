package com.team01.uber.driver.security;

public abstract class AuthHandler {

    protected AuthHandler next;

    public void setNext(AuthHandler next) {
        this.next = next;
    }

    public abstract void handle(AuthContext ctx);

    protected void passToNext(AuthContext ctx) {
        if (next != null) {
            next.handle(ctx);
        }
    }
}
