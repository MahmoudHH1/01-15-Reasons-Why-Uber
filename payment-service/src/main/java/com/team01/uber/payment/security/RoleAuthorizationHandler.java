package com.team01.uber.payment.security;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

public class RoleAuthorizationHandler extends AuthHandler {

    private final List<String> allowedRoles;

    public RoleAuthorizationHandler(List<String> allowedRoles) {
        this.allowedRoles = allowedRoles;
    }

    @Override
    public boolean handle(AuthContext ctx) {
        if (!allowedRoles.contains(ctx.getRole())) {
            try {
                ctx.getResponse().setStatus(HttpServletResponse.SC_FORBIDDEN);
                ctx.getResponse().getWriter().write("Access denied: insufficient role");
            } catch (IOException e) {
                ctx.getResponse().setStatus(HttpServletResponse.SC_FORBIDDEN);
            }
            return false;
        }

        return handleNext(ctx);
    }
}
