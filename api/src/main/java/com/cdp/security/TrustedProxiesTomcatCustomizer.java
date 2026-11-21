package com.cdp.security;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import jakarta.servlet.ServletException;

import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.RemoteIpValve;
import org.apache.catalina.valves.ValveBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * 在 Spring 自動建立 RemoteIpValve 之後，把它的信任範圍換成 {@code CDP_TRUSTED_PROXIES}（auth.md §3），
 * 其餘屬性（protocolHeader 等）保留。多於一個 valve 就拒絕啟動；沒有 valve（例如 mock web 的測試 context，
 * 不會真的收請求）時不處理 {@code X-Forwarded-For}，也就是一律只用連線端位址（fail closed）。
 *
 * <p>Tomcat 以字串比對代理位址，所以另在 RemoteIpValve 前放一個 valve，把標頭裡的 IP 文字正規化成
 * {@code getHostAddress()} 寫法（IPv6 壓縮與否、大小寫、IPv4-mapped 視為同一位址，auth.md §3「比對」）。
 */
@Component
class TrustedProxiesTomcatCustomizer implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>, Ordered {

    private static final Logger log = LoggerFactory.getLogger(TrustedProxiesTomcatCustomizer.class);

    private final TrustedProxies trustedProxies;

    TrustedProxiesTomcatCustomizer(TrustedProxies trustedProxies) {
        this.trustedProxies = trustedProxies;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        RemoteIpValve found = null;
        for (Valve valve : factory.getEngineValves()) {
            if (valve instanceof RemoteIpValve remoteIp) {
                if (found != null) {
                    throw new IllegalStateException("找到多個 RemoteIpValve，無法套用 " + TrustedProxies.VAR);
                }
                found = remoteIp;
            }
        }
        if (found == null) {
            log.warn("no RemoteIpValve on the web server factory; X-Forwarded-For is not processed");
            return;
        }
        found.setInternalProxies(trustedProxies.toInternalProxiesRegex());
        found.setTrustedProxies(null);

        List<Valve> valves = new ArrayList<>();
        for (Valve valve : factory.getEngineValves()) {
            if (valve == found) {
                valves.add(new ForwardedForNormalizer(found.getRemoteIpHeader()));
            }
            valves.add(valve);
        }
        factory.setEngineValves(valves);
    }

    /** 把標頭裡每個 IP 文字改寫成正規寫法；非 IP 的片段原樣保留。 */
    static final class ForwardedForNormalizer extends ValveBase {

        private final String header;

        ForwardedForNormalizer(String header) {
            super(true);
            this.header = header == null || header.isBlank() ? "X-Forwarded-For" : header;
        }

        @Override
        public void invoke(Request request, Response response) throws IOException, ServletException {
            Enumeration<String> values = request.getHeaders(header);
            if (values != null && values.hasMoreElements()) {
                List<String> tokens = new ArrayList<>();
                for (String line : Collections.list(values)) {
                    for (String token : line.split(",", -1)) {
                        tokens.add(normalize(token));
                    }
                }
                var headers = request.getCoyoteRequest().getMimeHeaders();
                headers.removeHeader(header);
                headers.addValue(header).setString(String.join(", ", tokens));
            }
            getNext().invoke(request, response);
        }

        static String normalize(String token) {
            String t = token.strip();
            InetAddress addr = TrustedProxies.literal(t);
            return addr == null ? t : addr.getHostAddress();
        }
    }
}
