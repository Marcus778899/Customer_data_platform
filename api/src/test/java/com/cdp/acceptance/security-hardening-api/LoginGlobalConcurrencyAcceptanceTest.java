package com.cdp.acceptance.securityhardeningapi;

import static com.cdp.acceptance.security.AuthHttp.PASSWORD;
import static com.cdp.acceptance.security.AuthHttp.credentials;
import static com.cdp.acceptance.security.AuthHttp.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.cdp.acceptance.security.AuthHttp;
import com.cdp.acceptance.security.AuthHttp.Resp;
import com.cdp.acceptance.security.SecurityTestSupport;

/**
 * security-hardening-api 驗收（auth.md §3 v0.3.0「全域併發」）：同時在比對密碼的請求達上限（CPU 核心數）→ 新請求
 * {@code 429 LOGIN_RATE_LIMITED}、{@code Retry-After: 1}，不計數；其餘請求照常處理。
 * 以遠多於核心數的請求（各用不同來源與 email，避開 email、來源鎖定）同時送出，至少要有請求被全域併發上限擋下。
 * 精確上限由 backend 單元測試覆蓋。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "cdp.login.max-failures=50",
            "cdp.login.lockout=PT15M",
            "cdp.login.ip-max-failures=1000"
        })
@ActiveProfiles("test")
@Import(SecurityTestSupport.class)
class LoginGlobalConcurrencyAcceptanceTest {

    @LocalServerPort int port;

    @Test
    void burst_beyond_cpu_count_is_rejected_with_retry_after_1_and_does_not_count() throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();
        int n = Math.min(Math.max(cores * 12, 48), 400);
        String real = uniqueEmail();
        AuthHttp.createUserId(port, real, "VIEWER");

        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Resp>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String ip = "10.77." + (i / 200) + "." + (i % 200 + 1);
                String email = uniqueEmail();
                Callable<Resp> call = () -> {
                    start.await();
                    return AuthHttp.sendRaw(
                            port,
                            "POST",
                            "/api/v1/auth/login",
                            Map.of("X-Forwarded-For", ip),
                            credentials(email, "definitely-wrong-password"));
                };
                futures.add(pool.submit(call));
            }
            start.countDown();
            int limited = 0;
            int wrong = 0;
            for (Future<Resp> f : futures) {
                Resp r = f.get();
                if (r.status() == 429) {
                    limited++;
                    assertThat(r.code()).isEqualTo("LOGIN_RATE_LIMITED");
                    assertThat(r.headers().firstValue("Retry-After")).contains("1");
                } else {
                    assertThat(r.status()).as(r.body()).isEqualTo(401);
                    wrong++;
                }
            }
            assertThat(limited).as("同時 %d 個登入請求，至少要有被全域併發上限擋下的", n).isGreaterThan(0);
            assertThat(wrong).as("上限內的請求照常比對").isGreaterThan(0);
        } finally {
            pool.shutdownNow();
        }
        // 爆量結束後恢復正常（被擋的請求不計數、不鎖任何人）
        Resp ok = AuthHttp.sendRaw(
                port,
                "POST",
                "/api/v1/auth/login",
                Map.of("X-Forwarded-For", "10.78.0.1"),
                credentials(real, PASSWORD));
        assertThat(ok.status()).isEqualTo(204);
    }
}
