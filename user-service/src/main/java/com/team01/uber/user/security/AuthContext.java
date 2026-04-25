package com.team01.uber.user.security;

import com.team01.uber.user.model.User;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AuthContext {

    private final HttpServletRequest request;
    private String token;
    private User user;

    public AuthContext(HttpServletRequest request) {
        this.request = request;
    }
}