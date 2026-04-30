package com.team01.uber.ride.security;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;

public class RoleAuthorizationHandler extends AuthHandler {
    private final List<String> allowedRoles;

    public RoleAuthorizationHandler(List<String> allowedRoles) {
        this.allowedRoles = allowedRoles;
    }

    @Override
    protected boolean process(AuthContext ctx) throws Exception {
        if (ctx.getRole() == null || (!allowedRoles.isEmpty() && !allowedRoles.contains(ctx.getRole()))) {
            ctx.getResponse().setStatus(HttpServletResponse.SC_FORBIDDEN);
            ctx.getResponse().getWriter().write("Insufficient role");
            return false;
        }
        return true;
    }
}