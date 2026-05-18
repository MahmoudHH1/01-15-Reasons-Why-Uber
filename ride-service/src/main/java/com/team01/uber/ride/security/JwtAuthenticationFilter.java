package com.team01.uber.ride.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import com.team01.uber.ride.client.UserClient;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserClient userClient;

    public JwtAuthenticationFilter(JwtService jwtService, UserClient userClient) {
        this.jwtService = jwtService;
        this.userClient = userClient;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.equals("/api/rides/health") || path.startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        AuthContext ctx = new AuthContext(request, response);

        AuthHandler head = new TokenExtractionHandler();
        head.setNext(new SignatureValidationHandler(jwtService))
            .setNext(new UserLoaderHandler(userClient))
            .setNext(new RoleAuthorizationHandler(List.of("RIDER", "DRIVER", "ADMIN")));

        try {
            if (head.handle(ctx)) {
                var auth = new UsernamePasswordAuthenticationToken(
                        ctx.getEmail(),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + ctx.getRole()))
                );
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);

                request.setAttribute("authContext", ctx);

                filterChain.doFilter(request, response);
            }
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write(e.getMessage());
        }
    }
}