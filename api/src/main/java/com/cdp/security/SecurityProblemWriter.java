package com.cdp.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import com.cdp.common.web.ErrorCode;
import com.cdp.common.web.RequestIdFilter;

/** 在 security filter 層（還沒進 MVC）直接寫契約 §5 的 problem+json。 */
final class SecurityProblemWriter {

    private SecurityProblemWriter() {}

    static void write(HttpServletResponse response, ErrorCode code, String detail) throws IOException {
        HttpStatus status = code.status();
        String requestId = response.getHeader(RequestIdFilter.HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = MDC.get(RequestIdFilter.MDC_KEY);
        }
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        if (response.getHeader(RequestIdFilter.HEADER) == null) {
            response.setHeader(RequestIdFilter.HEADER, requestId);
        }
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        String body = "{\"type\":\"about:blank\",\"title\":\"" + escape(status.getReasonPhrase())
                + "\",\"status\":" + status.value()
                + ",\"detail\":\"" + escape(detail)
                + "\",\"code\":\"" + code.name()
                + "\",\"request_id\":\"" + escape(requestId) + "\"}";
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
