package com.cdp.acceptance.maskingdefaultapi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.cdp.acceptance.engine.EngineTestSupport;

import tools.jackson.databind.JsonNode;

/** 設 {@code -}：全遮，姓名不明文故 profile_name 為 null（不回遮罩值），識別碼照遮罩。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "cdp.engine.poll-interval=PT1H",
            "cdp.relay.poll-interval=PT1H",
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "cdp.trace.plain-keys=-"
        })
@ActiveProfiles("test")
@Import(EngineTestSupport.class)
class MaskingDefaultAllMaskedTest extends MaskingDefaultBase {

    @Test
    void dashMakesNameNullAndIdentifierMasked() throws Exception {
        String pid = profile("{\"name\":\"Alice Chen\"}");
        identifier(pid, "EMAIL", "alice@example.com");
        String eid = enrollment(pid);

        JsonNode item = listItem(pid);
        assertThat(item.has("profile_name")).isTrue();
        assertThat(item.get("profile_name").isNull()).isTrue();
        assertThat(item.get("profile_identifier").get("value").asString()).isEqualTo("a***@example.com");
        assertThat(item.get("profile_identifier").get("masked").asBoolean()).isTrue();

        JsonNode d = detail(eid);
        assertThat(d.get("profile_name").isNull()).isTrue();
    }
}
