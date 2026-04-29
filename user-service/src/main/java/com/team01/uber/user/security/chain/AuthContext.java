package com.team01.uber.user.security.chain;

import com.team01.uber.user.model.User;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AuthContext {

    private final HttpServletRequest request;
    private String token;
    private Claims claims;
    private User authenticatedUser;

    public AuthContext(HttpServletRequest request) {
        this.request = request;
    }
}