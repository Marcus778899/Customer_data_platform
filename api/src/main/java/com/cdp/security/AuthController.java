package com.cdp.security;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 契約 §1.2 客服登入。帳密比對用常數時間；登入成功換新 session id（防 session fixation）。 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthProperties properties;

    public AuthController(AuthProperties properties) {
        this.properties = properties;
    }

    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> login(@RequestBody LoginRequest body, HttpServletRequest request) {
        throw new UnsupportedOperationException("TODO(BE-04)");
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        throw new UnsupportedOperationException("TODO(BE-04)");
    }

    @GetMapping("/me")
    public CurrentUserResponse me() {
        throw new UnsupportedOperationException("TODO(BE-04)");
    }
}
