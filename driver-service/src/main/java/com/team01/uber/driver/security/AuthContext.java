package com.team01.uber.driver.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AuthContext {

    private final HttpServletRequest request;
    private String token;
    private String email;
    private Long uid;
    private String role;
    private Integer errorStatus;
    private String errorMessage;

    public AuthContext(HttpServletRequest request) {
        this.request = request;
    }
}
