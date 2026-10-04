package com.project.ticket.global.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.ticket.domain.user.entity.UserRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthenticationFilterTest {
    private static final String SECRET = "test-secret-key-for-jwt-auth-filter-1234567890";

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void expiredTokenReturnsUnauthorizedWithoutCallingNextFilter() throws Exception {
        JwtProvider jwtProvider = new JwtProvider(SECRET, -1, 7, Clock.systemUTC());
        String expiredToken = jwtProvider.createAccessToken(1L, UserRole.FAN);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtProvider);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        FilterChain filterChain = (servletRequest, servletResponse) -> chainCalled.set(true);

        request.addHeader("Authorization", "Bearer " + expiredToken);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(chainCalled).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
