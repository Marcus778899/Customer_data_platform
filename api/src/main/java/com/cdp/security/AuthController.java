package com.cdp.security;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cdp.common.web.ApiException;
import com.cdp.common.web.ErrorCode;
import com.cdp.common.web.FieldError;
import com.cdp.user.AppUser;
import com.cdp.user.PasswordHasher;
import com.cdp.user.UserService;

/**
 * auth.md §3 登入、登出、me、改密碼。登入比對 BCrypt（帳號不存在、停用也跑一次等價成本的比對），失敗一律同一個
 * 401；成功換新 session id（防 session fixation），session 只存 user_id。限速在密碼比對之前檢查，鎖定中不跑 BCrypt。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String INVALID_CREDENTIALS_DETAIL = "帳號或密碼錯誤";

    private final UserService users;
    private final PasswordHasher hasher;
    private final LoginRateLimiter limiter;
    private final SessionCsrfTokenRepository csrf;
    private final Cookie sessionCookie;

    public AuthController(
            UserService users,
            PasswordHasher hasher,
            LoginRateLimiter limiter,
            SessionCsrfTokenRepository csrf,
            ServerProperties serverProperties) {
        this.users = users;
        this.hasher = hasher;
        this.limiter = limiter;
        this.csrf = csrf;
        this.sessionCookie = serverProperties.getServlet().getSession().getCookie();
    }

    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> login(
            @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        List<FieldError> errors = new ArrayList<>();
        if (body.email() == null || body.email().isBlank()) {
            errors.add(new FieldError("email", "必填"));
        }
        if (body.password() == null || body.password().isEmpty()) {
            errors.add(new FieldError("password", "必填"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "請求內容有誤", errors);
        }
        String key = body.email().trim().toLowerCase(Locale.ROOT);

        Duration locked = limiter.remainingLock(key);
        if (!locked.isZero()) {
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(LoginRateLimiter.retryAfterSeconds(locked)));
            SecurityProblemWriter.write(response, ErrorCode.LOGIN_RATE_LIMITED, "登入嘗試過於頻繁，請稍後再試。");
            return null;
        }

        AppUser user = users.findByEmail(key).orElse(null);
        boolean passwordOk = user != null ? hasher.matches(body.password(), user.passwordHash())
                : hasher.matchesDummy(body.password());
        if (user == null || !user.active() || !passwordOk) {
            limiter.recordFailure(key);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, INVALID_CREDENTIALS_DETAIL);
        }
        limiter.reset(key);

        HttpSession old = request.getSession(false);
        if (old != null) {
            old.invalidate();
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(SessionAuthenticationFilter.USER_ID_ATTRIBUTE, user.userId());
        csrf.rotate(request, response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        csrf.clear(response);
        ResponseCookie cleared = ResponseCookie.from(
                        sessionCookie.getName() != null ? sessionCookie.getName() : "JSESSIONID", "")
                .path(sessionCookie.getPath() != null ? sessionCookie.getPath() : "/")
                .maxAge(0)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .build();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cleared.toString()).build();
    }

    /** 回應一定帶新的 XSRF-TOKEN（登入成功後前端先呼叫這支取值）。 */
    @GetMapping("/me")
    public CurrentUserResponse me(HttpServletRequest request, HttpServletResponse response) {
        SessionUser user = currentUser();
        csrf.rotate(request, response);
        return new CurrentUserResponse(user.userId(), user.email(), user.displayName(), user.role());
    }

    @PostMapping(path = "/password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> changePassword(@RequestBody PasswordChangeRequest body) {
        users.changePassword(currentUser().userId(), body.currentPassword(), body.newPassword());
        return ResponseEntity.noContent().build();
    }

    private static SessionUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof SessionUser user) {
            return user;
        }
        throw new ApiException(ErrorCode.UNAUTHENTICATED, "缺少有效的憑證");
    }
}
