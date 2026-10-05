package com.project.ticket.domain.user.controller;

import com.project.ticket.domain.user.dto.UserResponse;
import com.project.ticket.domain.auth.service.AuthService;
import com.project.ticket.domain.auth.controller.AuthCookies;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import com.project.ticket.domain.user.service.UserService;
import com.project.ticket.global.auth.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserService userService;

    private final AuthService auth;
    private final AuthCookies cookies;

    public UserController(UserService userService, AuthService auth, AuthCookies cookies) {
        this.userService = userService;
        this.auth = auth;
        this.cookies = cookies;
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> withdraw(@AuthenticationPrincipal AuthenticatedUser user, HttpServletResponse response) {
        auth.withdraw(user.userId());
        cookies.clearRefresh(response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public UserResponse findMe(@AuthenticationPrincipal AuthenticatedUser user) {
        return userService.findMe(user.userId());
    }
}
