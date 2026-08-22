package com.cdp.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/**
 * Phase 0 驗收條件：三種事件來源（客戶行為／外部系統回寫／內部操作）都能用同一份事件契約描述。
 *
 * <p>本測試涵蓋結構層與各來源的必填規則。以下屬執行期語意，不在此處驗證：
 * 去重（tenant_id + event_id）、遲到事件門檻、未來時間拒絕、簽章驗證、速率限制。
 *
 * <p>見 docs/spec/01-event-contract.md。
 */
class EventContractSchemaTest {

    private static final String SCHEMA_PATH = "/schema/event.schema.json";
    private static final Path FIXTURE_DIR = Path.of("src/test/resources/event/sources");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonSchema SCHEMA = loadSchema();

    private static JsonSchema loadSchema() {
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().build();
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        try (InputStream in = EventContractSchemaTest.class.getResourceAsStream(SCHEMA_PATH)) {
            if (in == null) {
                throw new IllegalStateException("找不到 schema：" + SCHEMA_PATH);
            }
            return factory.getSchema(in, config);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Stream<Path> fixtures() throws IOException {
        try (Stream<Path> files = Files.list(FIXTURE_DIR)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList().stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    @DisplayName("三種來源與回流事件都能用同一份契約描述")
    void fixtureValidatesAgainstSchema(Path fixture) throws IOException {
        JsonNode event = MAPPER.readTree(fixture.toFile());
        Set<ValidationMessage> errors = SCHEMA.validate(event);

        assertThat(errors).as("%s 未通過 schema：%n%s", fixture.getFileName(), format(errors)).isEmpty();
    }

    @Test
    @DisplayName("三種 source_type 都有對應的 fixture")
    void allThreeSourceTypesAreCovered() throws IOException {
        List<String> sourceTypes = fixtures().map(EventContractSchemaTest::readSourceType).distinct().sorted().toList();

        assertThat(sourceTypes)
                .as("Phase 0 驗收要求三種來源都能描述，缺一個就不算通過")
                .containsExactly("CUSTOMER_BEHAVIOR", "EXTERNAL_SYSTEM", "INTERNAL_OPERATION");
    }

    @Test
    @DisplayName("EXTERNAL_SYSTEM 沒有 source_id 會被擋下")
    void externalSystemRequiresSourceId() throws IOException {
        JsonNode event = MAPPER.readTree(base("EXTERNAL_SYSTEM").replace("\"source_id\": \"sys-a\",", ""));

        assertThat(format(SCHEMA.validate(event)))
                .as("外部系統事件必須指明是哪個系統送的（§4）")
                .contains("source_id");
    }

    @Test
    @DisplayName("INTERNAL_OPERATION 沒有 context.actor_id 會被擋下")
    void internalOperationRequiresActorId() throws IOException {
        String json = base("INTERNAL_OPERATION").replace("\"actor_id\": \"user_1\",", "");

        assertThat(format(SCHEMA.validate(MAPPER.readTree(json))))
                .as("內部操作事件會直接推進旅程，必須查得到是誰送的（§4）")
                .contains("actor_id");
    }

    @Test
    @DisplayName("客戶行為事件不該帶 actor_id——那代表 source_type 標錯了")
    void customerBehaviorMustNotCarryActor() throws IOException {
        String json = base("CUSTOMER_BEHAVIOR");

        assertThat(SCHEMA.validate(MAPPER.readTree(json)))
                .as("actor_id 對客戶行為事件不適用（§4）")
                .isNotEmpty();
    }

    @Test
    @DisplayName("identity 兩者皆無會被擋下")
    void identityRequiresProfileIdOrIdentifiers() throws IOException {
        String json =
                """
                {
                    "event_id": "01JCXYZEVENT00000000000001",
                    "tenant_id": "01JCXYZ0000TENANT0000000001",
                    "source_type": "CUSTOMER_BEHAVIOR",
                    "name": "product_viewed",
                    "occurred_at": "2026-08-21T09:15:32Z",
                    "identity": {}
                }
                """;

        assertThat(SCHEMA.validate(MAPPER.readTree(json)))
                .as("profile_id 與 identifiers 至少擇一（§5）")
                .isNotEmpty();
    }

    @Test
    @DisplayName("回流事件缺 enrollment_id 會被擋下")
    void deliveryFeedbackRequiresEnrollmentId() throws IOException {
        String json =
                """
                {
                    "event_id": "01JCXYZEVENT00000000000002",
                    "tenant_id": "01JCXYZ0000TENANT0000000001",
                    "source_type": "EXTERNAL_SYSTEM",
                    "source_id": "channel-line",
                    "name": "message_opened",
                    "occurred_at": "2026-08-21T12:41:05Z",
                    "identity": { "profile_id": "01JCXYZPROFILE00000000001" },
                        "properties": {
                        "journey_id": "01JCXYZJOURNEY0000000001",
                        "node_id": "send_vip",
                        "template_id": "tpl_vip_cart",
                        "channel_type": "LINE"
                    }
                }
                """;

        assertThat(format(SCHEMA.validate(MAPPER.readTree(json))))
                .as("少了 enrollment_id，WaitForEventNode 就只能等「任何訊息被開啟」（§10）")
                .contains("enrollment_id");
    }

    @Test
    @DisplayName("事件名稱必須是 snake_case")
    void eventNameMustBeSnakeCase() throws IOException {
        JsonNode event = MAPPER.readTree(base("EXTERNAL_SYSTEM").replace("\"order_completed\"", "\"OrderCompleted\""));

        assertThat(format(SCHEMA.validate(event))).contains("name");
    }

    private static String base(String sourceType) {
        String context =
                switch (sourceType) {
                    case "INTERNAL_OPERATION" -> "{ \"actor_id\": \"user_1\", \"trust_level\": \"VERIFIED\" }";
                    case "CUSTOMER_BEHAVIOR" -> "{ \"actor_id\": \"user_1\" }";
                    default -> "{ \"trust_level\": \"VERIFIED\" }";
                };
        return """
                {
                    "event_id": "01JCXYZEVENT00000000000003",
                    "tenant_id": "01JCXYZ0000TENANT0000000001",
                    "source_type": "%s",
                    "source_id": "sys-a",
                    "name": "order_completed",
                    "occurred_at": "2026-08-21T09:15:32Z",
                    "identity": { "profile_id": "01JCXYZPROFILE00000000001" },
                    "context": %s
                }
                """
                .formatted(sourceType, context);
    }

    private static String readSourceType(Path p) {
        try {
            return MAPPER.readTree(p.toFile()).path("source_type").asText();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String format(Set<ValidationMessage> errors) {
        return errors.stream().map(ValidationMessage::toString).sorted().reduce("", (a, b) -> a + "\n  " + b);
    }
}
