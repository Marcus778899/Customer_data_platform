package com.cdp.security;

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cdp.common.web.ApiException;
import com.cdp.common.web.ErrorCode;
import com.cdp.common.web.FieldError;

/** 契約 §1.2 客服登入。帳密比對用常數時間；登入成功換新 session id（防 session fixation）。 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthProperties properties;
    private final Cookie cookieConfig;

    public AuthController(AuthProperties properties, ServerProperties serverProperties) {
        this.properties = properties;
        this.cookieConfig = serverProperties.getServlet().getSession().getCookie();
    }

    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> login(@RequestBody LoginRequest body, HttpServletRequest request) {
        List<FieldError> errors = new ArrayList<>();
        if (body.username() == null || body.username().isBlank()) {
            errors.add(new FieldError("username", "必填"));
        }
        if (body.password() == null || body.password().isBlank()) {
            errors.add(new FieldError("password", "必填"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "請求內容有誤", errors);
        }
        // 兩項都比、不短路：帳號錯與密碼錯耗時相同、回應相同
        boolean userOk = SecretMatcher.matches(body.username(), properties.supportUsername());
        boolean passOk = SecretMatcher.matches(body.password(), properties.supportPassword());
        if (!(userOk & passOk)) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "帳號或密碼錯誤");
        }
        HttpSession old = request.getSession(false);
        if (old != null) {
            old.invalidate();
        }
        HttpSession session = request.getSession(true);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                properties.supportUsername(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + Role.SUPPORT.name()))));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        ResponseCookie cleared = ResponseCookie.from(cookieConfig.getName() != null ? cookieConfig.getName() : "JSESSIONID", "")
                .path(cookieConfig.getPath() != null ? cookieConfig.getPath() : "/")
                .maxAge(0)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .build();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cleared.toString()).build();
    }

    @GetMapping("/me")
    public CurrentUserResponse me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return new CurrentUserResponse(auth.getName());
    }
}
