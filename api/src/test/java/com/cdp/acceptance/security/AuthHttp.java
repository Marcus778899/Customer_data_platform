package com.cdp.acceptance.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 驗收共用：auth.md 的 HTTP 輔助（email 登入、cookie 罐、CSRF 標頭）。使用者一律經管理金鑰 {@code POST /api/v1/users}
 * 建立，不依賴任何預設帳號。cookie 以字串 {@code "CDP_SESSION=…; XSRF-TOKEN=…"} 傳遞；寫入請求由 {@link #send} 自動帶上
 * {@code X-XSRF-TOKEN}（值取自 cookie 字串當下的 XSRF-TOKEN），要測缺值或錯值時用 {@link #sendRaw}。
 */
public final class AuthHttp {

    public static final String ADMIN_KEY = "Bearer test-admin-key";
    public static final String INGEST_KEY = "Bearer test-ingest-key";
    public static final String PASSWORD = "correct-horse-battery";

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    private AuthHttp() {}

    /** 回應（status、標頭、本文）。 */
    public record Resp(int status, HttpHeaders headers, String body) {
        public JsonNode json() {
            try {
                return JSON.readTree(body);
            } catch (Exception e) {
                throw new AssertionError("not JSON: " + body, e);
            }
        }

        public String code() {
            return json().path("code").asString();
        }

        public List<String> setCookies() {
            return headers.allValues("Set-Cookie");
        }

        /** 指定名稱的 Set-Cookie 整行；沒有回 null。 */
        public String setCookie(String name) {
            return setCookies().stream().filter(c -> c.startsWith(name + "=")).findFirst().orElse(null);
        }
    }

    public static String url(int port, String path) {
        return "http://localhost:" + port + path;
    }

    /** 不做任何自動處理的請求；headers 可為 null。 */
    public static Resp sendRaw(int port, String method, String path, Map<String, String> headers, String body)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(port, path)));
        if (headers != null) headers.forEach(b::header);
        if (body != null) b.header("Content-Type", "application/json");
        b.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.headers(), r.body());
    }

    /** auth（Authorization 標頭值，可 null）與 cookie（可 null）；非 GET 且 cookie 內有 XSRF-TOKEN 時自動帶 CSRF 標頭。 */
    public static Resp send(int port, String method, String path, String auth, String cookie, String body)
            throws Exception {
        Map<String, String> h = new LinkedHashMap<>();
        if (auth != null) h.put("Authorization", auth);
        if (cookie != null) h.put("Cookie", cookie);
        String x = xsrf(cookie);
        if (x != null && !"GET".equals(method) && !"HEAD".equals(method)) h.put("X-XSRF-TOKEN", x);
        return sendRaw(port, method, path, h, body);
    }

    public static Resp bearer(int port, String method, String path, String auth, String body) throws Exception {
        return send(port, method, path, auth, null, body);
    }

    public static Resp session(int port, String method, String path, String cookie, String body) throws Exception {
        return send(port, method, path, null, cookie, body);
    }

    public static String xsrf(String cookie) {
        return cookieValue(cookie, "XSRF-TOKEN");
    }

    public static String cookieValue(String cookie, String name) {
        if (cookie == null) return null;
        for (String part : cookie.split(";\\s*")) {
            if (part.startsWith(name + "=")) return part.substring(name.length() + 1);
        }
        return null;
    }

    /** 把回應的 Set-Cookie 套用到 cookie 字串（Max-Age=0 或空值 = 刪除）。 */
    public static String merge(String cookie, Resp r) {
        Map<String, String> jar = new LinkedHashMap<>();
        if (cookie != null && !cookie.isBlank()) {
            for (String part : cookie.split(";\\s*")) {
                int i = part.indexOf('=');
                if (i > 0) jar.put(part.substring(0, i), part.substring(i + 1));
            }
        }
        for (String sc : r.setCookies()) {
            String[] attrs = sc.split(";\\s*");
            int i = attrs[0].indexOf('=');
            if (i <= 0) continue;
            String name = attrs[0].substring(0, i);
            String value = attrs[0].substring(i + 1);
            boolean expired = value.isEmpty();
            for (String a : attrs) {
                if (a.equalsIgnoreCase("Max-Age=0") || a.startsWith("Expires=Thu, 01 Jan 1970")) expired = true;
            }
            if (expired) jar.remove(name);
            else jar.put(name, value);
        }
        StringBuilder sb = new StringBuilder();
        jar.forEach((k, v) -> sb.append(sb.length() == 0 ? "" : "; ").append(k).append('=').append(v));
        return sb.toString();
    }

    public static String uniqueEmail() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "@Example.com";
    }

    public static String credentials(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    public static String userBody(String email, String name, String role, String password) {
        return "{\"email\":\"" + email + "\",\"display_name\":\"" + name + "\",\"role\":\"" + role
                + "\",\"password\":\"" + password + "\"}";
    }

    /** 管理金鑰建立使用者，回應原樣回傳。 */
    public static Resp createUser(int port, String email, String name, String role, String password)
            throws Exception {
        return bearer(port, "POST", "/api/v1/users", ADMIN_KEY, userBody(email, name, role, password));
    }

    /** 建立並確認 201，回傳 user_id。 */
    public static String createUserId(int port, String email, String role) throws Exception {
        Resp r = createUser(port, email, "QA " + role, role, PASSWORD);
        assertThat(r.status()).as("create user: %s", r.body()).isEqualTo(201);
        return r.json().path("user_id").asString();
    }

    /** 登入並呼叫 /auth/me 取得 XSRF-TOKEN；回傳 cookie 字串。 */
    public static String login(int port, String email, String password) throws Exception {
        Resp r = sendRaw(port, "POST", "/api/v1/auth/login", null, credentials(email, password));
        assertThat(r.status()).as("login: %s", r.body()).isEqualTo(204);
        String cookie = merge(null, r);
        assertThat(cookieValue(cookie, "CDP_SESSION")).isNotBlank();
        Resp me = send(port, "GET", "/api/v1/auth/me", null, cookie, null);
        assertThat(me.status()).as("me after login: %s", me.body()).isEqualTo(200);
        cookie = merge(cookie, me);
        assertThat(xsrf(cookie)).as("me must set XSRF-TOKEN").isNotBlank();
        return cookie;
    }

    /** 建一個指定角色的新使用者並登入；回傳 cookie 字串。 */
    public static String loginNewUser(int port, String role) throws Exception {
        String email = uniqueEmail();
        createUserId(port, email, role);
        return login(port, email, PASSWORD);
    }
}
