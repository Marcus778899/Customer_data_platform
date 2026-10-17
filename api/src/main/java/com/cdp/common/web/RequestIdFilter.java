package com.cdp.common.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.cdp.common.IdGenerator;

/** 每個請求一個 request id（契約 §5）：沿用合法的 X-Request-Id，否則產生；放 MDC 與回應標頭。 */
@Component
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "request_id";

    private final IdGenerator ids;

    public RequestIdFilter(IdGenerator ids) {
        this.ids = ids;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }
}
