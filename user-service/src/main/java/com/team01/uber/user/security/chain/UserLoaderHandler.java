package com.team01.uber.user.security.chain;

import com.team01.uber.user.model.User;
import com.team01.uber.user.repository.UserRepository;
import org.springframework.security.core.AuthenticationException;

public class UserLoaderHandler extends AuthHandler {

    private final UserRepository userRepository;

    public UserLoaderHandler(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public void handle(AuthContext ctx) throws AuthenticationException {
        String email = ctx.getClaims().getSubject();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() ->
                        new AuthenticationException("User not found: " + email) {});
        ctx.setAuthenticatedUser(user);
        handleNext(ctx);
    }
}