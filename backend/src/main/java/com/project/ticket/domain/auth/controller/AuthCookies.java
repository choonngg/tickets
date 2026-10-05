package com.project.ticket.domain.auth.controller;

import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class AuthCookies {
    private final boolean secure;

    public AuthCookies(@Value("${auth.cookie-secure:true}") boolean secure) {
        this.secure = secure;
    }

    public String cookie(String name, String value, String path, Duration age) {
        return ResponseCookie.from(name, value).httpOnly(true).secure(secure).sameSite("Lax")
                .path(path).maxAge(age).build().toString();
    }

    public void clearRefresh(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("refresh_token", "", "/api/auth", Duration.ZERO));
    }
}
