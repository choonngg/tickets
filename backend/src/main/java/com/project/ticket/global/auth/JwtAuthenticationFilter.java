package com.project.ticket.global.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.jsonwebtoken.JwtException;
import java.io.IOException;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtProvider jwtProvider;
    private final SecurityErrorHandler securityErrorHandler;

    public JwtAuthenticationFilter(JwtProvider jwtProvider, SecurityErrorHandler securityErrorHandler) {
        this.jwtProvider = jwtProvider;
        this.securityErrorHandler = securityErrorHandler;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // These APIs authenticate only the RT Cookie, even if an expired AT is supplied.
        return "POST".equals(request.getMethod()) &&
                ((request.getContextPath() + "/api/auth/refresh").equals(request.getRequestURI())
                        || (request.getContextPath() + "/api/auth/logout").equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            try {
                authenticate(authorization.substring(BEARER_PREFIX.length()));
            } catch (JwtException | IllegalArgumentException exception) {
                SecurityContextHolder.clearContext();
                securityErrorHandler.commence(request, response, null);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(String token) {
        AuthenticatedUser user = jwtProvider.parse(token);
        var authentication = new UsernamePasswordAuthenticationToken(
                user,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()))
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
