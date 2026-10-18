package com.cdp.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import com.cdp.common.web.ApiExceptionHandler;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void methodNotAllowedHasCodeAndAllowHeaderWithoutErrors() throws Exception {
        ResponseEntity<Map<String, Object>> r = handler.handleUnexpected(
                new HttpRequestMethodNotSupportedException("GET", List.of("POST")));
        assertThat(r.getStatusCode().value()).isEqualTo(405);
        assertThat(r.getBody()).containsEntry("code", "METHOD_NOT_ALLOWED").doesNotContainKey("errors");
        assertThat(r.getHeaders().getAllow()).contains(HttpMethod.POST);
    }

    @Test
    void unsupportedMediaTypeHasAcceptHeader() throws Exception {
        ResponseEntity<Map<String, Object>> r = handler.handleUnexpected(
                new HttpMediaTypeNotSupportedException(
                        MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON)));
        assertThat(r.getStatusCode().value()).isEqualTo(415);
        assertThat(r.getBody()).containsEntry("code", "UNSUPPORTED_MEDIA_TYPE").doesNotContainKey("errors");
        assertThat(r.getHeaders().getAccept()).contains(MediaType.APPLICATION_JSON);
    }

    @Test
    void securityExceptionsAreRethrown() {
        assertThatThrownBy(() -> handler.handleUnexpected(new AccessDeniedException("x")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> handler.handleUnexpected(new BadCredentialsException("x")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void unexpectedIs500WithoutLeak() throws Exception {
        ResponseEntity<Map<String, Object>> r = handler.handleUnexpected(new IllegalStateException("secret"));
        assertThat(r.getStatusCode().value()).isEqualTo(500);
        assertThat(r.getBody()).containsEntry("code", "INTERNAL_ERROR");
        assertThat(r.getBody().toString()).doesNotContain("secret");
    }
}
