package com.cdp.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SessionCsrfTokenRepositoryTest {

    private final SessionCsrfTokenRepository repo = new SessionCsrfTokenRepository(true);

    private static String tokenOf(MockHttpServletResponse res) {
        String line = res.getHeaders("Set-Cookie").get(0);
        return line.substring("XSRF-TOKEN=".length(), line.indexOf(';'));
    }

    @Test
    void rotate_sets_non_httponly_strict_cookie_and_binds_to_session() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.getSession(true);
        MockHttpServletResponse res = new MockHttpServletResponse();
        repo.rotate(req, res);
        String line = res.getHeaders("Set-Cookie").get(0);
        assertThat(line).contains("Path=/", "Secure", "SameSite=Strict").doesNotContain("HttpOnly");

        req.setCookies(new Cookie("XSRF-TOKEN", tokenOf(res)));
        assertThat(repo.loadToken(req)).isNotNull();
        assertThat(repo.loadToken(req).getToken()).isEqualTo(tokenOf(res));
    }

    @Test
    void missing_cookie_or_other_session_or_old_value_loads_nothing() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.getSession(true);
        MockHttpServletResponse first = new MockHttpServletResponse();
        repo.rotate(req, first);
        MockHttpServletResponse second = new MockHttpServletResponse();
        repo.rotate(req, second);

        assertThat(repo.loadToken(req)).isNull();
        req.setCookies(new Cookie("XSRF-TOKEN", tokenOf(first)));
        assertThat(repo.loadToken(req)).as("rotated-away value").isNull();
        req.setCookies(new Cookie("XSRF-TOKEN", tokenOf(second)));
        assertThat(repo.loadToken(req)).isNotNull();

        MockHttpServletRequest otherSession = new MockHttpServletRequest();
        otherSession.getSession(true);
        otherSession.setCookies(new Cookie("XSRF-TOKEN", tokenOf(second)));
        assertThat(repo.loadToken(otherSession)).isNull();
        MockHttpServletRequest noSession = new MockHttpServletRequest();
        noSession.setCookies(new Cookie("XSRF-TOKEN", tokenOf(second)));
        assertThat(repo.loadToken(noSession)).isNull();
    }

    @Test
    void rotate_without_session_and_save_token_do_nothing() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        repo.rotate(req, res);
        repo.saveToken(repo.generateToken(req), req, res);
        assertThat(res.getHeaders("Set-Cookie")).isEmpty();
    }
}
