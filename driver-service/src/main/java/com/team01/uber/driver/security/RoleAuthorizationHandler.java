package com.team01.uber.driver.security;

public class RoleAuthorizationHandler extends AuthHandler {

    private final String requiredRole;

    public RoleAuthorizationHandler(String requiredRole) {
        this.requiredRole = requiredRole;
    }

    @Override
    public void handle(AuthContext ctx) {
        if ("USER".equals(requiredRole)) {
            passToNext(ctx);
            return;
        }
        if (!requiredRole.equals(ctx.getRole())) {
            ctx.setErrorStatus(403);
            ctx.setErrorMessage("Insufficient role");
            return;
        }
        passToNext(ctx);
    }
}
