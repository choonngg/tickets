package com.project.ticket.domain.auth.controller;

import com.project.ticket.domain.auth.dto.TokenRefreshResponse;
import com.project.ticket.domain.auth.service.AuthService;
import com.project.ticket.global.exception.BusinessException;
import com.project.ticket.global.exception.ErrorCode;
import com.project.ticket.infra.google.GoogleOAuthClient;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final GoogleOAuthClient google;
    private final String clientId;
    private final String redirectUri;
    private final AuthCookies cookies;
    private final SecureRandom random = new SecureRandom();

    public AuthController(AuthService auth, GoogleOAuthClient google,
            @Value("${google.client-id}") String clientId,
            @Value("${google.redirect-uri}") String redirectUri,
            AuthCookies cookies) {
        this.auth = auth;
        this.google = google;
        this.clientId = clientId;
        this.redirectUri = redirectUri;
        this.cookies = cookies;
    }

    @GetMapping("/google/login")
    public ResponseEntity<Void> googleLogin() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        URI location = UriComponentsBuilder.fromUriString("https://accounts.google.com/o/oauth2/v2/auth")
                .queryParam("client_id", clientId).queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code").queryParam("scope", "openid email profile")
                .queryParam("state", state).build().encode().toUri();
        return ResponseEntity.status(HttpStatus.FOUND).location(location).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookies.cookie("oauth_state", state, "/api/auth/google", Duration.ofMinutes(10)))
                .build();
    }

    @GetMapping("/google/callback")
    public ResponseEntity<Void> googleCallback(
            @CookieValue(name = "oauth_state", required = false) String cookieState,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "code", required = false) String code,
            HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.cookie("oauth_state", "", "/api/auth/google", Duration.ZERO));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        if (cookieState == null || cookieState.isBlank() || state == null || state.isBlank()
                || !MessageDigest.isEqual(cookieState.getBytes(StandardCharsets.UTF_8), state.getBytes(StandardCharsets.UTF_8))
                || code == null || code.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        var issued = auth.authenticateGoogle(google.exchange(code));
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookies.cookie("refresh_token", issued.refreshToken(), "/api/auth", issued.refreshMaxAge()));
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/"))
                .cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenRefreshResponse> refresh(
            @CookieValue(name = "refresh_token", required = false) String raw, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        var result = auth.refresh(raw); // Transaction, including reuse revoke, has committed.
        if (result.error() != null) {
            if (result.error() == ErrorCode.REAUTH_REQUIRED) cookies.clearRefresh(response);
            throw new BusinessException(result.error());
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookies.cookie("refresh_token", result.tokens().refreshToken(), "/api/auth", result.tokens().refreshMaxAge()))
                .body(TokenRefreshResponse.bearer(result.tokens().accessToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = "refresh_token", required = false) String raw, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        try {
            auth.logout(raw);
        } catch (BusinessException exception) {
            if (exception.errorCode() == ErrorCode.REAUTH_REQUIRED) cookies.clearRefresh(response);
            throw exception;
        }
        cookies.clearRefresh(response);
        return ResponseEntity.noContent().build();
    }

}
