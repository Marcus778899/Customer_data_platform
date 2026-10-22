package com.cdp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import com.cdp.common.web.ApiException;
import com.cdp.common.web.ErrorCode;
import com.cdp.user.AppUser;
import com.cdp.user.PasswordHasher;
import com.cdp.user.UserRole;
import com.cdp.user.UserService;
import com.cdp.user.UserStatus;

class AuthControllerTest {

    private final UserService users = mock(UserService.class);
    private final PasswordHasher hasher = mock(PasswordHasher.class);
    private final LoginRateLimiter limiter =
            new LoginRateLimiter(new LoginProperties(2, Duration.ofMinutes(5)), Clock.systemUTC());
    private final SessionCsrfTokenRepository csrf = new SessionCsrfTokenRepository(false);
    private final AuthController controller =
            new AuthController(users, hasher, limiter, csrf, serverProperties());

    private static ServerProperties serverProperties() {
        ServerProperties sp = new ServerProperties();
        sp.getServlet().getSession().getCookie().setName("CDP_SESSION");
        sp.getServlet().getSession().getCookie().setPath("/api");
        return sp;
    }

    private static AppUser user(UserStatus status) {
        Instant t = Instant.parse("2026-10-22T00:00:00Z");
        return new AppUser("01JD0USER0000000000000001", "ops@example.com", "Ops", "$2a$hash", UserRole.EDITOR, status, t, t);
    }

    private HttpStatus loginStatus(String email, String password, MockHttpServletRequest req, MockHttpServletResponse res)
            throws Exception {
        try {
            var r = controller.login(new LoginRequest(email, password), req, res);
            return r == null ? HttpStatus.valueOf(res.getStatus()) : HttpStatus.valueOf(r.getStatusCode().value());
        } catch (ApiException e) {
            return e.code().status();
        }
    }

    @Test
    void success_creates_fresh_session_with_only_user_id_and_new_xsrf_cookie() throws Exception {
        when(users.findByEmail("ops@example.com")).thenReturn(Optional.of(user(UserStatus.ACTIVE)));
        when(hasher.matches("secret-password-1", "$2a$hash")).thenReturn(true);
        MockHttpServletRequest req = new MockHttpServletRequest();
        String oldId = req.getSession(true).getId();
        MockHttpServletResponse res = new MockHttpServletResponse();

        var r = controller.login(new LoginRequest(" Ops@Example.com ", "secret-password-1"), req, res);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        var session = req.getSession(false);
        assertThat(session.getId()).isNotEqualTo(oldId);
        assertThat(session.getAttribute(SessionAuthenticationFilter.USER_ID_ATTRIBUTE))
                .isEqualTo("01JD0USER0000000000000001");
        assertThat(res.getHeaders("Set-Cookie")).anyMatch(c -> c.startsWith("XSRF-TOKEN=") && c.contains("SameSite=Strict"));
    }

    @Test
    void unknown_disabled_and_wrong_password_are_the_same_401_and_always_run_one_comparison() throws Exception {
        when(users.findByEmail("none@example.com")).thenReturn(Optional.empty());
        when(users.findByEmail("off@example.com")).thenReturn(Optional.of(user(UserStatus.DISABLED)));
        when(users.findByEmail("ops@example.com")).thenReturn(Optional.of(user(UserStatus.ACTIVE)));
        when(hasher.matches(anyString(), anyString())).thenReturn(true).thenReturn(false);

        for (String email : new String[] {"none@example.com", "off@example.com", "ops@example.com"}) {
            assertThatThrownBy(() -> controller.login(
                            new LoginRequest(email, "whatever-password"), new MockHttpServletRequest(), new MockHttpServletResponse()))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
                        assertThat(e.getMessage()).isEqualTo("帳號或密碼錯誤");
                    });
        }
        verify(hasher).matchesDummy("whatever-password");
    }

    @Test
    void locked_email_gets_429_with_retry_after_and_never_runs_bcrypt() throws Exception {
        when(users.findByEmail(anyString())).thenReturn(Optional.empty());
        for (int i = 0; i < 2; i++) {
            assertThat(loginStatus("a@example.com", "x-wrong-password", new MockHttpServletRequest(),
                            new MockHttpServletResponse()))
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        org.mockito.Mockito.clearInvocations(hasher, users);

        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(loginStatus("A@Example.com", "correct-password-1", new MockHttpServletRequest(), res))
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(res.getHeader("Retry-After")).isEqualTo("300");
        assertThat(res.getContentAsString()).contains("LOGIN_RATE_LIMITED");
        verify(hasher, never()).matches(any(), any());
        verify(hasher, never()).matchesDummy(any());
        verify(users, never()).findByEmail(anyString());
    }

    @Test
    void blank_fields_are_validation_failed_and_not_counted() {
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> controller.login(
                            new LoginRequest("a@example.com", null), new MockHttpServletRequest(), new MockHttpServletResponse()))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                        assertThat(e.errors()).extracting("field").containsExactly("password");
                    });
        }
        assertThatThrownBy(() -> controller.login(
                        new LoginRequest(null, "x"), new MockHttpServletRequest(), new MockHttpServletResponse()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errors()).extracting("field")
                        .containsExactly("email"));
        assertThat(limiter.remainingLock("a@example.com")).isZero();
    }

    @Test
    void logout_invalidates_session_and_clears_both_cookies() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        var session = (MockHttpSession) req.getSession(true);
        MockHttpServletResponse res = new MockHttpServletResponse();
        var r = controller.logout(req, res);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(session.isInvalid()).isTrue();
        assertThat(r.getHeaders().getFirst("Set-Cookie")).contains("CDP_SESSION=", "Max-Age=0", "Path=/api");
        assertThat(res.getHeaders("Set-Cookie")).anyMatch(c -> c.startsWith("XSRF-TOKEN=") && c.contains("Max-Age=0"));
        assertThat(controller.logout(new MockHttpServletRequest(), new MockHttpServletResponse()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }
}
