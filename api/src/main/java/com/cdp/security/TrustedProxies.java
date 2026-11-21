package com.cdp.security;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 信任代理清單（auth.md §3「來源 IP」）：恰為 {@code -}（不信任任何代理），或 1～10 項確切 IP 位址。
 * 不符格式丟 {@link IllegalStateException}（訊息寫變數名稱、第幾項、哪條規則，不回顯值）。
 * 只做文字位址解析，不會觸發 DNS 查詢。
 */
final class TrustedProxies {

    static final String VAR = "CDP_TRUSTED_PROXIES";
    static final int MAX_ITEMS = 10;
    private static final String NEVER = "(?!)";
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9A-Fa-f:.]+");

    private final List<InetAddress> addresses;

    private TrustedProxies(List<InetAddress> addresses) {
        this.addresses = List.copyOf(addresses);
    }

    static TrustedProxies parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("設定 " + VAR + " 未設定或為空白：必填，不信任任何代理請填 -");
        }
        String trimmed = raw.strip();
        if (trimmed.equals("-")) {
            return new TrustedProxies(List.of());
        }
        String[] items = trimmed.split(",", -1);
        if (items.length > MAX_ITEMS) {
            throw new IllegalStateException("設定 " + VAR + " 項目過多：最多 " + MAX_ITEMS + " 項");
        }
        List<InetAddress> out = new ArrayList<>();
        for (int i = 0; i < items.length; i++) {
            out.add(parseItem(items[i].strip(), i + 1));
        }
        return new TrustedProxies(out);
    }

    private static InetAddress parseItem(String item, int no) {
        String where = "設定 " + VAR + " 第 " + no + " 項";
        if (item.isEmpty()) {
            throw new IllegalStateException(where + "為空項");
        }
        if (item.equals("-")) {
            throw new IllegalStateException(where + "：- 不可與位址混用");
        }
        InetAddress addr = literal(item);
        if (addr == null) {
            throw new IllegalStateException(
                    where + "不是確切的 IPv4／IPv6 文字位址（不收網段、主機名稱、方括號、zone id 與佔位值）");
        }
        if (addr.isAnyLocalAddress()) {
            throw new IllegalStateException(where + "是萬用位址，不可作為代理位址");
        }
        return addr;
    }

    /** 文字位址解析：先以字元與結構判斷，通過才交給 InetAddress（此時不會查 DNS）；IPv4-mapped 由 JDK 轉成 IPv4。 */
    static InetAddress literal(String text) {
        if (text == null) {
            return null;
        }
        try {
            var m = IPV4.matcher(text);
            if (m.matches()) {
                for (int g = 1; g <= 4; g++) {
                    if (Integer.parseInt(m.group(g)) > 255) {
                        return null;
                    }
                }
                return InetAddress.getByName(text);
            }
            if (text.indexOf(':') >= 0 && IPV6_CHARS.matcher(text).matches()) {
                return InetAddress.getByName(text);
            }
        } catch (UnknownHostException | NumberFormatException e) {
            return null;
        }
        return null;
    }

    boolean isNone() {
        return addresses.isEmpty();
    }

    /** Tomcat RemoteIpValve internalProxies 用的正規式；{@code -} 輸出永不符合的 {@code (?!)}（空字串 = 信任全部，不可用）。 */
    String toInternalProxiesRegex() {
        if (addresses.isEmpty()) {
            return NEVER;
        }
        List<String> parts = new ArrayList<>();
        for (InetAddress a : addresses) {
            String q = Pattern.quote(a.getHostAddress());
            parts.add(a instanceof Inet6Address ? q + "(?:%[^%]+)?" : q);
        }
        return String.join("|", parts);
    }

    @Configuration(proxyBeanMethods = false)
    static class Config {
        @Bean
        TrustedProxies trustedProxies(TrustedProxiesProperties properties) {
            return parse(properties.trustedProxies());
        }
    }
}
