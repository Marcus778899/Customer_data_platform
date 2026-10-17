package com.cdp.common.web;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 所有錯誤轉成契約 §5 格式（含 code、request_id；400 另含 errors）。Spring 內建的 4xx（缺參數、型別錯、JSON
 * 無法解析、無此路由）轉 VALIDATION_FAILED／NOT_FOUND；DB 連不上轉 SERVICE_UNAVAILABLE；其餘 INTERNAL_ERROR，不外洩訊息。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApi(ApiException ex) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }
}
