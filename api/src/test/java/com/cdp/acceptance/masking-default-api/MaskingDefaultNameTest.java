package com.cdp.acceptance.maskingdefaultapi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.cdp.acceptance.engine.EngineTestSupport;

import tools.jackson.databind.JsonNode;

/**
 * 列表與詳情的 profile_name、profile_identifier（journey-engine §4.1、§4.4）。
 * 明文清單覆寫為 name（等同內建預設）：姓名明文、email 與手機遮罩。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "cdp.engine.poll-interval=PT1H",
            "cdp.relay.poll-interval=PT1H",
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "cdp.trace.plain-keys=name"
        })
@ActiveProfiles("test")
@Import(EngineTestSupport.class)
class MaskingDefaultNameTest extends MaskingDefaultBase {

    @Test
    void listCarriesPlainNameAndMaskedEmailIdentifier() throws Exception {
        String pid = profile("{\"name\":\"Alice Chen\"}");
        identifier(pid, "EMAIL", "alice@example.com");
        enrollment(pid);

        JsonNode item = listItem(pid);
        assertThat(item.get("profile_name").asString()).isEqualTo("Alice Chen");
        JsonNode ident = item.get("profile_identifier");
        assertThat(ident.get("type").asString()).isEqualTo("EMAIL");
        assertThat(ident.get("value").asString()).isEqualTo("a***@example.com");
        assertThat(ident.get("masked").asBoolean()).isTrue();
    }

    @Test
    void phoneIdentifierIsMasked() throws Exception {
        String pid = profile("{\"name\":\"Bob\"}");
        identifier(pid, "PHONE", "+886912345678");
        enrollment(pid);

        JsonNode ident = listItem(pid).get("profile_identifier");
        assertThat(ident.get("type").asString()).isEqualTo("PHONE");
        assertThat(ident.get("value").asString()).isEqualTo("***");
        assertThat(ident.get("masked").asBoolean()).isTrue();
    }

    @Test
    void firstIdentifierBySortedTypeThenValue() throws Exception {
        String pid = profile("{\"name\":\"Carol\"}");
        identifier(pid, "PHONE", "+886911111111");
        identifier(pid, "EMAIL", "zed@example.com");
        identifier(pid, "EMAIL", "amy@example.com");
        enrollment(pid);

        JsonNode ident = listItem(pid).get("profile_identifier");
        assertThat(ident.get("type").asString()).isEqualTo("EMAIL");
        assertThat(ident.get("value").asString()).isEqualTo("a***@example.com");
    }

    @Test
    void detailCarriesSameFields() throws Exception {
        String pid = profile("{\"name\":\"Dora\"}");
        identifier(pid, "EMAIL", "dora@example.com");
        String eid = enrollment(pid);

        JsonNode d = detail(eid);
        assertThat(d.get("profile_name").asString()).isEqualTo("Dora");
        assertThat(d.get("profile_identifier").get("value").asString()).isEqualTo("d***@example.com");
        assertThat(d.get("profile_identifier").get("masked").asBoolean()).isTrue();
    }

    @Test
    void nonStringNameIsNull() throws Exception {
        String pid = profile("{\"name\":12345}");
        enrollment(pid);

        JsonNode item = listItem(pid);
        assertThat(item.has("profile_name")).isTrue();
        assertThat(item.get("profile_name").isNull()).isTrue();
    }

    @Test
    void profileWithoutNameOrIdentifierGivesExplicitNulls() throws Exception {
        String pid = profile("{}");
        enrollment(pid);

        JsonNode item = listItem(pid);
        assertThat(item.has("profile_name")).isTrue();
        assertThat(item.get("profile_name").isNull()).isTrue();
        assertThat(item.has("profile_identifier")).isTrue();
        assertThat(item.get("profile_identifier").isNull()).isTrue();
    }

    @Test
    void enrollmentWithoutProfileRowGivesNulls() throws Exception {
        String pid = EngineTestSupport.ulid();
        enrollment(pid);

        JsonNode item = listItem(pid);
        assertThat(item.get("profile_name").isNull()).isTrue();
        assertThat(item.get("profile_identifier").isNull()).isTrue();
    }
}
