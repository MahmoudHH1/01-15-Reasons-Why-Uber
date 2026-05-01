package com.team01.uber.payment.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;

public class UserLoaderHandler extends AuthHandler {

    private final JdbcTemplate jdbcTemplate;

    public UserLoaderHandler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean handle(AuthContext ctx) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?",
                Integer.class,
                ctx.getEmail()
        );

        if (count == null || count == 0) {
            try {
                ctx.getResponse().setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                ctx.getResponse().getWriter().write("User not found");
            } catch (IOException e) {
                ctx.getResponse().setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            }
            return false;
        }

        return handleNext(ctx);
    }
}
