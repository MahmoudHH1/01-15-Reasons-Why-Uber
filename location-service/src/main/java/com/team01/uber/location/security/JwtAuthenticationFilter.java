package com.team01.uber.location.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.team01.uber.contracts.feign.UserServiceClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserServiceClient userServiceClient;

    public JwtAuthenticationFilter(JwtService jwtService, UserServiceClient userServiceClient) {
        this.jwtService = jwtService;
        this.userServiceClient = userServiceClient;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/api/locations/health") || path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        AuthContext ctx = new AuthContext(request, response);

        // Build the Chain of Responsibility
        AuthHandler head = new TokenExtractionHandler();
        head.setNext(new SignatureValidationHandler(jwtService))
            .setNext(new UserLoaderHandler(userServiceClient))
            .setNext(new RoleAuthorizationHandler(List.of("RIDER", "ADMIN")));

        boolean authenticated = false;
        try {
            if (head.handle(ctx)) {
                var auth = new UsernamePasswordAuthenticationToken(
                        ctx.getEmail(),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + ctx.getRole()))
                );
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
                authenticated = true;
            }
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write("Authentication processing error");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
