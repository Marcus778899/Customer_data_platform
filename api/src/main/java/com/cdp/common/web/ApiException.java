package com.cdp.common.web;

import java.util.List;

/** 業務錯誤；由 ApiExceptionHandler 轉成契約 §5 的 problem+json。 */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final transient List<?> errors;

    public ApiException(ErrorCode code, String detail) {
        this(code, detail, List.of());
    }

    /** errors 通常是 {@link FieldError}；journeys.md §3 的驗證錯誤用 com.cdp.journey.Issue（多帶 rule、node_id）。 */
    public ApiException(ErrorCode code, String detail, List<?> errors) {
        super(detail);
        this.code = code;
        this.errors = List.copyOf(errors);
    }

    public ErrorCode code() {
        return code;
    }

    public List<?> errors() {
        return errors;
    }
}
