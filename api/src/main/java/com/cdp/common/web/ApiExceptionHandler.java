package com.cdp.common.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import tools.jackson.core.JacksonException;

/**
 * 所有錯誤轉成契約 §5 格式（含 code、request_id；400 另含 errors）。Spring 內建的 4xx（缺參數、型別錯、JSON
 * 無法解析、無此路由）轉 VALIDATION_FAILED／NOT_FOUND；DB 連不上轉 SERVICE_UNAVAILABLE；其餘 INTERNAL_ERROR，不外洩訊息。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
        return problem(ex.code().status(), ex.code().name(), ex.getMessage(), ex.errors());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        if (ex instanceof HttpMessageNotReadableException notReadable) {
            String field = "body";
            if (notReadable.getCause() instanceof JacksonException je && !je.getPath().isEmpty()) {
                field = jsonPath(je);
            }
            return problem(
                    HttpStatus.BAD_REQUEST,
                    ErrorCode.VALIDATION_FAILED.name(),
                    "請求內容無法解析。",
                    List.of(new FieldError(field, "格式錯誤或缺少請求內容")));
        }
        if (ex instanceof MethodArgumentTypeMismatchException mismatch) {
            return problem(
                    HttpStatus.BAD_REQUEST,
                    ErrorCode.VALIDATION_FAILED.name(),
                    "參數格式錯誤。",
                    List.of(new FieldError(mismatch.getName(), "格式錯誤")));
        }
        if (ex instanceof DataAccessResourceFailureException) {
            log.error("資料庫無法使用", ex);
            return problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCode.SERVICE_UNAVAILABLE.name(),
                    "服務暫時無法使用，請稍後重試。",
                    List.of());
        }
        if (ex instanceof ErrorResponse er && er.getStatusCode().is4xxClientError()) {
            HttpStatusCode status = er.getStatusCode();
            if (status.value() == 404) {
                return problem(status, ErrorCode.NOT_FOUND.name(), "找不到這個資源。", List.of());
            }
            if (status.value() == 415) {
                return problem(
                        status,
                        ErrorCode.UNSUPPORTED_MEDIA_TYPE.name(),
                        "不支援的 Content-Type。",
                        List.of());
            }
            return problem(
                    status,
                    ErrorCode.VALIDATION_FAILED.name(),
                    "請求不正確。",
                    List.of(new FieldError("request", "參數缺漏或格式錯誤")));
        }
        log.error("未預期的錯誤", ex);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorCode.INTERNAL_ERROR.name(),
                "系統發生錯誤，請提供 request_id 回報。",
                List.of());
    }

    private static String jsonPath(JacksonException je) {
        StringBuilder sb = new StringBuilder();
        for (JacksonException.Reference ref : je.getPath()) {
            if (ref.getPropertyName() != null) {
                if (!sb.isEmpty()) {
                    sb.append('.');
                }
                sb.append(ref.getPropertyName());
            } else if (ref.getIndex() >= 0) {
                sb.append('[').append(ref.getIndex()).append(']');
            }
        }
        return sb.isEmpty() ? "body" : sb.toString();
    }

    /** 契約 §5：application/problem+json，type 固定 about:blank。 */
    private static ResponseEntity<Map<String, Object>> problem(
            HttpStatusCode status, String code, String detail, List<FieldError> errors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        HttpStatus known = HttpStatus.resolve(status.value());
        body.put("title", known == null ? "Error" : known.getReasonPhrase());
        body.put("status", status.value());
        body.put("code", code);
        body.put("detail", detail);
        body.put("request_id", MDC.get(RequestIdFilter.MDC_KEY));
        if (status.value() == 400) {
            body.put("errors", errors);
        }
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }
}
