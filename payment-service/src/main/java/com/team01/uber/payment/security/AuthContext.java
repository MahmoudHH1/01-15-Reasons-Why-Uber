package com.team01.uber.payment.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AuthContext {

    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private String token;
    private String email;
    private String role;
    private Long userId;

    public AuthContext(HttpServletRequest request, HttpServletResponse response) {
        this.request = request;
        this.response = response;
    }
}
