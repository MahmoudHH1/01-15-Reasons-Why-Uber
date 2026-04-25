package com.team01.uber.location.security;

import jakarta.servlet.http.HttpServletRequest;

public class AuthContext {

    private final HttpServletRequest request;
    private String token;
    private String email;
    private Long userId;
    private String role;

    public AuthContext(HttpServletRequest request) {
        this.request = request;
    }

    public HttpServletRequest getRequest() { return request; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
}
