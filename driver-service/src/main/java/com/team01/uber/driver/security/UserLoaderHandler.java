package com.team01.uber.driver.security;

import org.springframework.jdbc.core.JdbcTemplate;

public class UserLoaderHandler extends AuthHandler {

    private final JdbcTemplate jdbcTemplate;

    public UserLoaderHandler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void handle(AuthContext ctx) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM users WHERE email = ?",
                    Integer.class, ctx.getEmail());
            if (count == null || count == 0) {
                ctx.setErrorStatus(401);
                ctx.setErrorMessage("User not found");
                return;
            }
        } catch (Exception e) {
            ctx.setErrorStatus(401);
            ctx.setErrorMessage("User not found");
            return;
        }
        passToNext(ctx);
    }
}
