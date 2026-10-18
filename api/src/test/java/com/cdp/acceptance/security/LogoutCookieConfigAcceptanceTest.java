package com.cdp.acceptance.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** FIX-04 驗收：logout 清 cookie 的名稱與路徑取自 server.servlet.session.cookie.*，不寫死。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "server.servlet.session.cookie.name=CDP_ALT_SID",
            "server.servlet.session.cookie.path=/api/v1"
        })
@ActiveProfiles("test")
@Import(SecurityTestSupport.class)
class LogoutCookieConfigAcceptanceTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort int port;

    private HttpResponse<String> post(String path, String cookie, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (cookie != null) b.header("Cookie", cookie);
        if (body != null) b.header("Content-Type", "application/json");
        b.POST(body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void logoutClearsCookieWithConfiguredNameAndPath() throws Exception {
        HttpResponse<String> login = post(
                "/api/v1/auth/login",
                null,
                "{\"username\":\"test-support\",\"password\":\"test-support-password\"}");
        assertThat(login.statusCode()).isEqualTo(204);
        String issued = login.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith("CDP_ALT_SID="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("login did not use configured cookie name"));
        assertThat(issued).contains("Path=/api/v1");

        HttpResponse<String> out = post("/api/v1/auth/logout", issued.split(";")[0], null);
        assertThat(out.statusCode()).isEqualTo(204);
        List<String> cookies = out.headers().allValues("Set-Cookie");
        assertThat(cookies).noneMatch(c -> c.startsWith("CDP_SESSION="));
        String cleared = cookies.stream()
                .filter(c -> c.startsWith("CDP_ALT_SID="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("logout did not clear the configured cookie: " + cookies));
        assertThat(cleared).contains("Path=/api/v1").containsPattern("(?i)Max-Age=0|Expires=Thu, 01 Jan 1970");
    }
}
