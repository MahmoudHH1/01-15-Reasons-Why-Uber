package com.team01.uber.contracts.events;

public record UserRegisteredEvent(Long userId, String email, String role) {}
