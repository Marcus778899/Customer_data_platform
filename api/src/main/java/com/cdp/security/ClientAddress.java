package com.cdp.security;

import java.net.Inet6Address;
import java.net.InetAddress;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 登入限速的計數鍵（auth.md §3「來源 IP」）。信任代理與 {@code X-Forwarded-For} 由 Tomcat RemoteIpValve 處理
 * （信任範圍見 {@link TrustedProxiesTomcatCustomizer}），這裡只讀 {@code getRemoteAddr()}：
 * IPv4 為完整位址、IPv4-mapped 視為 IPv4、IPv6 截到 /64（{@code 2001:db8:1:2::/64}）、無法解析為 {@code unknown}。
 */
final class ClientAddress {

    private ClientAddress() {}

    static String of(HttpServletRequest request) {
        String raw = request.getRemoteAddr();
        if (raw == null) {
            return "unknown";
        }
        String text = raw.strip();
        int zone = text.indexOf('%');
        if (zone >= 0) {
            text = text.substring(0, zone);
        }
        InetAddress addr = TrustedProxies.literal(text);
        if (addr == null) {
            return "unknown";
        }
        if (addr instanceof Inet6Address) {
            byte[] b = addr.getAddress();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                if (i > 0) {
                    sb.append(':');
                }
                sb.append(Integer.toHexString(((b[2 * i] & 0xff) << 8) | (b[2 * i + 1] & 0xff)));
            }
            return sb.append("::/64").toString();
        }
        return addr.getHostAddress();
    }
}
