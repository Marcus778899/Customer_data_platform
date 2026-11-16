package com.cdp.security;

import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 登入限速用的來源 IP（auth.md §3）。信任代理與 {@code X-Forwarded-For} 的解析交給 Tomcat RemoteIpValve
 * （{@code server.tomcat.remoteip.*}，信任範圍 CDP_TRUSTED_PROXIES）：只有來自信任代理的連線才會被換成
 * 轉發的用戶端位址，直接連線者帶的標頭不採信。所以這裡只讀 {@code getRemoteAddr()}。
 */
final class ClientAddress {

    private ClientAddress() {}

    static String of(HttpServletRequest request) {
        String addr = request.getRemoteAddr();
        return addr == null || addr.isBlank() ? "unknown" : addr.trim().toLowerCase(Locale.ROOT);
    }
}
