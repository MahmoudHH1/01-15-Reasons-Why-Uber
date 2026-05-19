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

        try {
            if (!head.handle(ctx)) {
                // A handler in the chain already wrote a 401/403/404/503 status + body.
                // Do NOT proceed to filterChain — that would let Spring's anyRequest().authenticated()
                // translate the missing Authentication into a 403.
                if (!response.isCommitted() && response.getStatus() == HttpServletResponse.SC_OK) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                }
                return;
            }

            var auth = new UsernamePasswordAuthenticationToken(
                    ctx.getEmail(),
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + ctx.getRole()))
            );
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("Invalid or unparseable token");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
