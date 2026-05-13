package com.team01.uber.contracts.dto;

public record UserDTO(
        Long id,
        String name,
        String email,
        String role,
        String status
) {}
