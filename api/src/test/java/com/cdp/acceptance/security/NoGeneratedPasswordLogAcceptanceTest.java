package com.cdp.acceptance.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** FIX-04 回歸：啟動日誌不得出現 Spring 預設產生的密碼。 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // 專屬屬性確保建立新的 context（不吃快取），啟動輸出才會被擷取
        properties = {"spring.rabbitmq.listener.simple.auto-startup=false", "fix04.log-test=no-generated-password"})
@ActiveProfiles("test")
@Import(SecurityTestSupport.class)
class NoGeneratedPasswordLogAcceptanceTest {

    @Test
    void startupLog_hasNoGeneratedSecurityPassword(CapturedOutput output) {
        assertThat(output.getAll()).contains("Started ").doesNotContain("Using generated security password");
    }
}
