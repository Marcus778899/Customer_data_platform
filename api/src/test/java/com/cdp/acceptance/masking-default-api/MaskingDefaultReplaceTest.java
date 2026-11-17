package com.cdp.acceptance.maskingdefaultapi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.cdp.acceptance.engine.EngineTestSupport;

import tools.jackson.databind.JsonNode;

/** 有設定時「取代」內建清單（不是疊加）：設 member_id 後 name 不再明文，member_id 識別碼明文。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "cdp.engine.poll-interval=PT1H",
            "cdp.relay.poll-interval=PT1H",
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "cdp.trace.plain-keys=member_id"
        })
@ActiveProfiles("test")
@Import(EngineTestSupport.class)
class MaskingDefaultReplaceTest extends MaskingDefaultBase {

    @Test
    void configuredListReplacesBuiltInDefault() throws Exception {
        String pid = profile("{\"name\":\"Alice Chen\"}");
        identifier(pid, "MEMBER_ID", "M-1001");
        enrollment(pid);

        JsonNode item = listItem(pid);
        assertThat(item.get("profile_name").isNull()).isTrue();
        JsonNode ident = item.get("profile_identifier");
        assertThat(ident.get("type").asString()).isEqualTo("MEMBER_ID");
        assertThat(ident.get("value").asString()).isEqualTo("M-1001");
        assertThat(ident.get("masked").asBoolean()).isFalse();
    }
}
