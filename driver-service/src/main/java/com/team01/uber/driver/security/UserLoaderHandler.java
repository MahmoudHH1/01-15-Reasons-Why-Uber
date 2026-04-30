package com.team01.uber.driver.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;

public class UserLoaderHandler extends AuthHandler {
    private final JdbcTemplate jdbcTemplate;

    public UserLoaderHandler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    protected boolean process(AuthContext ctx) throws Exception {
        if (ctx.getEmail() == null) {
            ctx.getResponse().setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            ctx.getResponse().getWriter().write("User not found");
            return false;
        }

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?",
                Integer.class,
                ctx.getEmail()
        );

        if (count == null || count == 0) {
            ctx.getResponse().setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            ctx.getResponse().getWriter().write("User not found in PG");
            return false;
        }
        return true;
    }
}
