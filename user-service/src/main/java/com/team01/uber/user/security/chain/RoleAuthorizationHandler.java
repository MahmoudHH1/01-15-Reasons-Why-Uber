package com.team01.uber.user.security.chain;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;

public class RoleAuthorizationHandler extends AuthHandler {

    @Override
    public void handle(AuthContext ctx)
            throws AuthenticationException, AccessDeniedException {
        // Role enforcement is delegated to SecurityConfig (.hasRole("ADMIN") etc.)
        // This handler completes the chain and is the extension point for
        // fine-grained role checks if needed in future milestones
        handleNext(ctx);
    }
}