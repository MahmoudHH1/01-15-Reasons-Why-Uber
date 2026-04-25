package com.team01.uber.user.dto;

import lombok.Getter;

@Getter
public class AuthResponse {
    private final String token;
    private final long expiresIn;

    public AuthResponse(String token, long expiresIn) {
        this.token = token;
        this.expiresIn = expiresIn;
    }
}