package com.cdp.acceptance.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.cdp.acceptance.engine.EngineTestSupport;

import tools.jackson.databind.JsonNode;

/** 追加 1：逗號切開、去前後空白、捨棄空字串與 "-"（它們不是明文鍵）。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "cdp.engine.poll-interval=PT1H",
            "cdp.relay.poll-interval=PT1H",
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "cdp.trace.plain-keys= cart_id , ,-,, safe "
        })
@ActiveProfiles("test")
@Import(EngineTestSupport.class)
class PlainKeysParsingTest extends PlainKeysSupport {

    @Test
    void trimmedKeysArePlain_dashAndEmptyAreNotPlainKeys() throws Exception {
        JsonNode props = propertiesOf(seed(sample()));
        assertThat(props.get("cart_id").asString()).isEqualTo("CART-1");
        assertThat(props.get("safe").asString()).isEqualTo("S-1");
        assertThat(props.get("-").asString()).isEqualTo("***");
        assertThat(props.get("").asString()).isEqualTo("***");
        assertThat(props.get("email").asString()).isEqualTo("a***@example.com");
    }
}
