package com.cdp.acceptance.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.cdp.acceptance.engine.EngineTestSupport;

import tools.jackson.databind.JsonNode;

/** 追加 1：值為空 = 全部遮罩。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "cdp.engine.poll-interval=PT1H",
            "cdp.relay.poll-interval=PT1H",
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "cdp.trace.plain-keys="
        })
@ActiveProfiles("test")
@Import(EngineTestSupport.class)
class PlainKeysEmptyTest extends PlainKeysSupport {

    @Test
    void emptyMeansEverythingMasked() throws Exception {
        JsonNode props = propertiesOf(seed(sample()));
        assertThat(props.get("cart_id").asString()).isEqualTo("***");
        assertThat(props.get("").asString()).isEqualTo("***");
        assertThat(props.get("-").asString()).isEqualTo("***");
    }
}
