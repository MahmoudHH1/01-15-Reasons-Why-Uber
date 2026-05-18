package com.team01.uber.driver.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.team01.uber.driver.client.UserClient;
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
    private final UserClient userClient;

    public JwtAuthenticationFilter(JwtService jwtService, UserClient userClient) {
        this.jwtService = jwtService;
        this.userClient = userClient;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.equals("/api/drivers/health") || path.startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        AuthHandler tokenExtractor = new TokenExtractionHandler();
        AuthHandler signatureValidator = new SignatureValidationHandler(jwtService);
        AuthHandler userLoader = new UserLoaderHandler(userClient);
        AuthHandler roleAuthorizer = new RoleAuthorizationHandler("USER");

        tokenExtractor.setNext(signatureValidator);
        signatureValidator.setNext(userLoader);
        userLoader.setNext(roleAuthorizer);

        AuthContext ctx = new AuthContext(request);
        tokenExtractor.handle(ctx);

        if (ctx.getErrorStatus() != null) {
            response.setStatus(ctx.getErrorStatus());
            response.setContentType("application/json");
            response.getWriter().write("{\"error\": \"" + ctx.getErrorMessage() + "\"}");
            return;
        }

        var auth = new UsernamePasswordAuthenticationToken(
                ctx.getEmail(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + ctx.getRole()))
        );
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(auth);

        filterChain.doFilter(request, response);
    }
}
