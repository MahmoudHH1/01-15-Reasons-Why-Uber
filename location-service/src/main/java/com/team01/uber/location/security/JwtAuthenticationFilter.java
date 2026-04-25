package com.team01.uber.location.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final JdbcTemplate jdbcTemplate;

    public JwtAuthenticationFilter(JwtService jwtService, JdbcTemplate jdbcTemplate) {
        this.jwtService = jwtService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getServletPath().equals("/api/locations/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        AuthContext ctx = new AuthContext(request);

        // ── Handler 1: TokenExtractionHandler ──
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("Missing or malformed Authorization header");
            return;
        }
        ctx.setToken(authHeader.substring(7));

        // ── Handler 2: SignatureValidationHandler ──
        if (!jwtService.isTokenValid(ctx.getToken())) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("Invalid or expired token");
            return;
        }

        // ── Handler 3: UserLoaderHandler ──
        String email = jwtService.extractEmail(ctx.getToken());
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email);
        if (count == null || count == 0) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("User not found");
            return;
        }
        ctx.setEmail(email);

        // ── Handler 4: RoleAuthorizationHandler ──
        String role = jwtService.extractRole(ctx.getToken());
        ctx.setRole(role);

        var auth = new UsernamePasswordAuthenticationToken(
                email,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(auth);

        filterChain.doFilter(request, response);
    }
}
