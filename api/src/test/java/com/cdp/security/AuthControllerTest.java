package com.cdp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import com.cdp.common.web.ApiException;
import com.cdp.common.web.ErrorCode;

class AuthControllerTest {

    private final AuthController controller = new AuthController(
            new AuthProperties("i", "a", "agent", "secret"), serverProperties());

    private static ServerProperties serverProperties() {
        ServerProperties sp = new ServerProperties();
        sp.getServlet().getSession().getCookie().setName("CDP_SESSION");
        sp.getServlet().getSession().getCookie().setPath("/api");
        return sp;
    }


    @Test
    void login_success_creates_fresh_session_with_context() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        String oldId = req.getSession(true).getId();
        var res = controller.login(new LoginRequest("agent", "secret"), req);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        var session = req.getSession(false);
        assertThat(session.getId()).isNotEqualTo(oldId);
        assertThat(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .isNotNull();
    }

    @Test
    void wrong_user_or_password_is_invalid_credentials() {
        for (LoginRequest r : new LoginRequest[] {
            new LoginRequest("agent", "x"), new LoginRequest("x", "secret")}) {
            assertThatThrownBy(() -> controller.login(r, new MockHttpServletRequest()))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
                        assertThat(e.getMessage()).isEqualTo("帳號或密碼錯誤");
                    });
        }
    }

    @Test
    void blank_fields_are_validation_failed() {
        assertThatThrownBy(() -> controller.login(new LoginRequest("agent", null), new MockHttpServletRequest()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.errors()).extracting("field").containsExactly("password");
                });
    }

    @Test
    void logout_invalidates_session_and_clears_cookie() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        var session = (org.springframework.mock.web.MockHttpSession) req.getSession(true);
        var res = controller.logout(req);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(session.isInvalid()).isTrue();
        assertThat(res.getHeaders().getFirst("Set-Cookie")).contains("CDP_SESSION=", "Max-Age=0", "Path=/api");
        assertThat(controller.logout(new MockHttpServletRequest()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }
}
