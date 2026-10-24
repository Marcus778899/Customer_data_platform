package com.cdp.common.web;

import org.springframework.http.HttpStatus;

/** 錯誤碼（契約 §5）。前端以 HTTP status 分 401／403／404／5xx，以 code 分細項。 */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    BATCH_TOO_LARGE(HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    CSRF_INVALID(HttpStatus.FORBIDDEN),
    LOGIN_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    EMAIL_TAKEN(HttpStatus.CONFLICT),
    LAST_ADMIN(HttpStatus.CONFLICT),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND),
    JOURNEY_NOT_FOUND(HttpStatus.NOT_FOUND),
    ENROLLMENT_NOT_FOUND(HttpStatus.NOT_FOUND),
    PROFILE_NOT_FOUND(HttpStatus.NOT_FOUND),
    IDENTIFIER_CONFLICT(HttpStatus.CONFLICT),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
