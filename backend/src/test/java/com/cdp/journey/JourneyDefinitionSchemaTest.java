package com.cdp.journey;

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
 * Phase 0 驗收條件：三個場景的旅程定義 JSON 必須通過 journey-definition.schema.json 驗證。
 *
 * <p>本測試只涵蓋**結構層**。圖規則（規則 1 後半、2、3、5、6、7、8、10~13）由 Java 圖驗證器負責，
 * 不在此處驗證——schema 表達不出來的東西，不該假裝它驗過了。
 *
 * <p>見 docs/spec/03-journey-definition.md §6 與 docs/spec/README.md「Phase 0 驗收條件」。
 */
class JourneyDefinitionSchemaTest {

    private static final String SCHEMA_PATH = "/schema/journey-definition.schema.json";
    private static final Path SCENARIO_DIR = Path.of("src/test/resources/journey/scenarios");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonSchema SCHEMA = loadSchema();

    private static JsonSchema loadSchema() {
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().build();
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        try (InputStream in = JourneyDefinitionSchemaTest.class.getResourceAsStream(SCHEMA_PATH)) {
            if (in == null) {
                throw new IllegalStateException("找不到 schema：" + SCHEMA_PATH);
            }
            return factory.getSchema(in, config);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Stream<Path> scenarios() throws IOException {
        try (Stream<Path> files = Files.list(SCENARIO_DIR)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList().stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    @DisplayName("計劃書附錄 C 的場景都能用旅程定義格式表達")
    void scenarioValidatesAgainstSchema(Path scenario) throws IOException {
        JsonNode journey = MAPPER.readTree(scenario.toFile());
        Set<ValidationMessage> errors = SCHEMA.validate(journey);

        assertThat(errors)
                .as("%s 未通過 schema：%n%s", scenario.getFileName(), format(errors))
                .isEmpty();
    }

    @Test
    @DisplayName("三個場景都已建立 fixture")
    void allThreeScenariosArePresent() throws IOException {
        List<String> names = scenarios().map(p -> p.getFileName().toString()).toList();

        assertThat(names)
                .as("Phase 0 驗收要求附錄 C 三個場景都能表達，缺一個就不算通過")
                .containsExactly(
                        "01-abandoned-cart.json", "02-birthday-gift.json", "03-dormant-winback.json");
    }

    @Test
    @DisplayName("未知的節點型別會被擋下，而且錯誤訊息指得出是哪個欄位")
    void unknownNodeTypeIsRejected() throws IOException {
        JsonNode journey = MAPPER.readTree(withNodeType("NotARealNode"));
        Set<ValidationMessage> errors = SCHEMA.validate(journey);

        assertThat(errors).isNotEmpty();
        assertThat(format(errors)).contains("type");
    }

    @Test
    @DisplayName("SendMessageNode 省略 on_skip 時仍必須有 skipped 出口")
    void omittedOnSkipStillRequiresSkippedPort() throws IOException {
        String journey =
                """
                {
                  "journey_id": "01JCXYZJOURNEY0000000004",
                  "tenant_id": "01JCXYZ0000TENANT0000000001",
                  "version": 1,
                  "name": "on_skip 預設值測試",
                  "status": "DRAFT",
                  "entry_node": "start_1",
                  "nodes": [
                    { "id": "start_1", "type": "StartNode", "config": {},
                      "outputs": { "next": "send_1" } },
                    { "id": "send_1", "type": "SendMessageNode",
                      "config": { "template_id": "tpl_x", "channel_id": "ch_x" },
                      "outputs": { "sent": "end_1" } },
                    { "id": "end_1", "type": "EndNode", "config": {}, "outputs": {} }
                  ]
                }
                """;

        Set<ValidationMessage> errors = SCHEMA.validate(MAPPER.readTree(journey));

        assertThat(format(errors))
                .as("on_skip 省略時預設為 CONTINUE，因此 skipped 出口必填（spec/03 §5）")
                .contains("skipped");
    }

    @Test
    @DisplayName("只有 StartNode 的 outputs 可以是陣列（多來源）")
    void onlyStartNodeMayFanOut() throws IOException {
        String multiSource =
                """
                {
                  "journey_id": "01JCXYZJOURNEY0000000005",
                  "tenant_id": "01JCXYZ0000TENANT0000000001",
                  "version": 1,
                  "name": "多來源旅程",
                  "status": "DRAFT",
                  "entry_node": "start_1",
                  "nodes": [
                    { "id": "start_1", "type": "StartNode", "config": {},
                      "outputs": { "next": ["src_1", "src_2"] } },
                    { "id": "src_1", "type": "EventSourceNode",
                      "config": { "event_name": "cart_item_added" },
                      "outputs": { "next": "end_1" } },
                    { "id": "src_2", "type": "ManualSourceNode",
                      "config": { "allow_api": true },
                      "outputs": { "next": "end_1" } },
                    { "id": "end_1", "type": "EndNode", "config": {}, "outputs": {} }
                  ]
                }
                """;

        assertThat(SCHEMA.validate(MAPPER.readTree(multiSource)))
                .as("StartNode 之下可掛多個 Source（spec/03 §4）")
                .isEmpty();

        String fannedOutCondition = multiSource.replace("\"next\": \"end_1\" }", "\"next\": [\"end_1\"] }");

        assertThat(SCHEMA.validate(MAPPER.readTree(fannedOutCondition)))
                .as("規則 14：非 StartNode 的 outputs 值必須是 string")
                .isNotEmpty();
    }

    private static String withNodeType(String type) {
        return """
                {
                  "journey_id": "01JCXYZJOURNEY0000000006",
                  "tenant_id": "01JCXYZ0000TENANT0000000001",
                  "version": 1,
                  "name": "未知型別測試",
                  "status": "DRAFT",
                  "entry_node": "start_1",
                  "nodes": [
                    { "id": "start_1", "type": "StartNode", "config": {},
                      "outputs": { "next": "x_1" } },
                    { "id": "x_1", "type": "%s", "config": {}, "outputs": {} }
                  ]
                }
                """
                .formatted(type);
    }

    private static String format(Set<ValidationMessage> errors) {
        return errors.stream().map(ValidationMessage::toString).sorted().reduce("", (a, b) -> a + "\n  " + b);
    }
}
