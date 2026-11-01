package com.cdp.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class ApiKeyAuthenticationFilterTest {

    private final ApiKeyAuthenticationFilter filter =
            new ApiKeyAuthenticationFilter(new AuthProperties("ing", "adm"));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletResponse run(String header, String[] roleOut) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (header != null) req.addHeader("Authorization", header);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = (rq, rs) -> {
            var a = SecurityContextHolder.getContext().getAuthentication();
            roleOut[0] = a == null ? null : a.getAuthorities().iterator().next().getAuthority();
        };
        filter.doFilter(req, res, chain);
        return res;
    }

    @Test
    void ingest_and_admin_keys_map_to_roles() throws Exception {
        String[] role = new String[1];
        run("Bearer ing", role);
        assertThat(role[0]).isEqualTo("ROLE_INGEST");
        run("Bearer adm", role);
        assertThat(role[0]).isEqualTo("ROLE_ADMIN");
    }

    @Test
    void no_header_passes_through_without_authentication() throws Exception {
        String[] role = new String[] {"unset"};
        MockHttpServletResponse res = run(null, role);
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(role[0]).isNull();
    }

    @Test
    void bad_headers_are_401_problem_json_and_do_not_reach_chain() throws Exception {
        for (String h : new String[] {"Bearer nope", "Bearer ", "Basic abc", "Bearer ingX"}) {
            String[] role = new String[] {"unset"};
            MockHttpServletResponse res = run(h, role);
            assertThat(res.getStatus()).isEqualTo(401);
            assertThat(role[0]).isEqualTo("unset");
            assertThat(res.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
            assertThat(res.getContentType()).startsWith("application/problem+json");
            assertThat(res.getContentAsString()).contains("\"code\":\"UNAUTHENTICATED\"").contains("request_id");
        }
    }

    @Test
    void secret_matcher_handles_nulls_and_mismatch() {
        assertThat(SecretMatcher.matches("a", "a")).isTrue();
        assertThat(SecretMatcher.matches("a", "b")).isFalse();
        assertThat(SecretMatcher.matches(null, "b")).isFalse();
        assertThat(SecretMatcher.matches("a", null)).isFalse();
    }
}
