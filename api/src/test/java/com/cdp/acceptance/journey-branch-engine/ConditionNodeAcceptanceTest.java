package com.cdp.acceptance.journeybranchengine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** journeys.md 第 4 節 ConditionNode：條件樹、比較語意（型別不合即不成立、不報錯）、欄位根、decision_reason 不含值。 */
class ConditionNodeAcceptanceTest extends BranchBase {

    private record Case(String name, Object condition, Map<String, Object> props, String expectedPort) {}

    private static Map<String, Object> nullProp(String key) {
        Map<String, Object> m = new HashMap<>();
        m.put(key, null);
        return m;
    }

    private static Case c(String name, Object condition, Map<String, Object> props, String port) {
        return new Case(name, condition, props, port);
    }

    private static String f(String key) {
        return "trigger.properties." + key;
    }

    /** start, src_1, cond_1(yes -> end_yes, no -> end_no)。 */
    private Map<String, Object> condJourney(String event, Object condition) {
        return def(
                Map.of(),
                List.of(
                        start(),
                        src(event, null, "cond_1"),
                        cond("cond_1", condition, "end_yes", "end_no"),
                        end("end_yes"),
                        end("end_no")));
    }

    @Test
    void comparisonSemantics_followContract() throws Exception {
        List<Case> cases = new ArrayList<>();
        // GT / GTE / LT / LTE：只在兩邊都是 number 時比較
        cases.add(c("GT 600>500", leaf(f("amount"), "GT", 500), props("amount", 600), "yes"));
        cases.add(c("GT 400>500", leaf(f("amount"), "GT", 500), props("amount", 400), "no"));
        cases.add(c("GT 500>500 邊界", leaf(f("amount"), "GT", 500), props("amount", 500), "no"));
        cases.add(c("GT 實際值為字串", leaf(f("amount"), "GT", 500), props("amount", "600"), "no"));
        cases.add(c("GT value 為字串", leaf(f("amount"), "GT", "500"), props("amount", 600), "no"));
        cases.add(c("GT 欄位不存在", leaf(f("amount"), "GT", 500), props(), "no"));
        cases.add(c("GTE 500>=500", leaf(f("amount"), "GTE", 500), props("amount", 500), "yes"));
        cases.add(c("LT 50<100", leaf(f("amount"), "LT", 100), props("amount", 50), "yes"));
        cases.add(c("LT 100<100", leaf(f("amount"), "LT", 100), props("amount", 100), "no"));
        cases.add(c("LTE 100<=100", leaf(f("amount"), "LTE", 100), props("amount", 100), "yes"));
        cases.add(c("LTE 布林", leaf(f("amount"), "LTE", 100), props("amount", true), "no"));
        // EQ / NEQ：JSON 值相等，500 與 "500" 不相等
        cases.add(c("EQ number", leaf(f("amount"), "EQ", 500), props("amount", 500), "yes"));
        cases.add(c("EQ 500 vs \"500\"", leaf(f("amount"), "EQ", 500), props("amount", "500"), "no"));
        cases.add(c("EQ string", leaf(f("tier"), "EQ", "GOLD"), props("tier", "GOLD"), "yes"));
        cases.add(c("EQ boolean", leaf(f("vip"), "EQ", true), props("vip", true), "yes"));
        cases.add(c("EQ 欄位不存在", leaf(f("tier"), "EQ", "GOLD"), props(), "no"));
        cases.add(c("NEQ 不同值", leaf(f("tier"), "NEQ", "GOLD"), props("tier", "SILVER"), "yes"));
        cases.add(c("NEQ 同值", leaf(f("tier"), "NEQ", "GOLD"), props("tier", "GOLD"), "no"));
        cases.add(c("NEQ 欄位不存在不成立", leaf(f("tier"), "NEQ", "GOLD"), props(), "no"));
        cases.add(c("NEQ 欄位為 null 不成立", leaf(f("tier"), "NEQ", "GOLD"), nullProp("tier"), "no"));
        // IN / NOT_IN
        cases.add(c("IN 命中", leaf(f("tier"), "IN", List.of("GOLD", "VIP")), props("tier", "VIP"), "yes"));
        cases.add(c("IN 未命中", leaf(f("tier"), "IN", List.of("GOLD", "VIP")), props("tier", "X"), "no"));
        cases.add(c("IN 型別不合", leaf(f("n"), "IN", List.of(1, 2)), props("n", "1"), "no"));
        cases.add(c("NOT_IN 未命中", leaf(f("tier"), "NOT_IN", List.of("GOLD")), props("tier", "X"), "yes"));
        cases.add(c("NOT_IN 命中", leaf(f("tier"), "NOT_IN", List.of("GOLD")), props("tier", "GOLD"), "no"));
        cases.add(c("NOT_IN 欄位不存在不成立", leaf(f("tier"), "NOT_IN", List.of("GOLD")), props(), "no"));
        // CONTAINS
        cases.add(c("CONTAINS 字串", leaf(f("s"), "CONTAINS", "wor"), props("s", "hello world"), "yes"));
        cases.add(c("CONTAINS 字串未含", leaf(f("s"), "CONTAINS", "xyz"), props("s", "hello world"), "no"));
        cases.add(c("CONTAINS 陣列", leaf(f("tags"), "CONTAINS", "b"), props("tags", List.of("a", "b")), "yes"));
        cases.add(c("CONTAINS 陣列型別不合", leaf(f("tags"), "CONTAINS", 1), props("tags", List.of("1")), "no"));
        cases.add(c("CONTAINS 實際值為數字", leaf(f("s"), "CONTAINS", "5"), props("s", 500), "no"));
        // IS_NULL / IS_NOT_NULL
        cases.add(c("IS_NULL 欄位不存在", leaf(f("x"), "IS_NULL", null), props(), "yes"));
        cases.add(c("IS_NULL 值為 null", leaf(f("x"), "IS_NULL", null), nullProp("x"), "yes"));
        cases.add(c("IS_NULL 有值", leaf(f("x"), "IS_NULL", null), props("x", 1), "no"));
        cases.add(c("IS_NOT_NULL 有值", leaf(f("x"), "IS_NOT_NULL", null), props("x", 1), "yes"));
        cases.add(c("IS_NOT_NULL 欄位不存在", leaf(f("x"), "IS_NOT_NULL", null), props(), "no"));
        cases.add(c("IS_NOT_NULL 值為 null", leaf(f("x"), "IS_NOT_NULL", null), nullProp("x"), "no"));
        // 群組
        cases.add(c(
                "AND 兩個都成立",
                group("AND", leaf(f("amount"), "GT", 500), leaf(f("tier"), "EQ", "GOLD")),
                props("amount", 600, "tier", "GOLD"),
                "yes"));
        cases.add(c(
                "AND 其一不成立",
                group("AND", leaf(f("amount"), "GT", 500), leaf(f("tier"), "EQ", "GOLD")),
                props("amount", 600, "tier", "X"),
                "no"));
        cases.add(c(
                "OR 其一成立",
                group("OR", leaf(f("amount"), "GT", 500), leaf(f("tier"), "EQ", "GOLD")),
                props("amount", 100, "tier", "GOLD"),
                "yes"));
        cases.add(c(
                "OR 都不成立",
                group("OR", leaf(f("amount"), "GT", 500), leaf(f("tier"), "EQ", "GOLD")),
                props("amount", 100, "tier", "X"),
                "no"));
        cases.add(c("NOT 子條件不成立", group("NOT", leaf(f("amount"), "GT", 500)), props("amount", 400), "yes"));
        cases.add(c("NOT 子條件成立", group("NOT", leaf(f("amount"), "GT", 500)), props("amount", 600), "no"));
        cases.add(c(
                "巢狀 AND(OR, NOT)",
                group(
                        "AND",
                        group("OR", leaf(f("a"), "EQ", 1), leaf(f("b"), "EQ", 1)),
                        group("NOT", leaf(f("c"), "EQ", 1))),
                props("a", 1, "b", 0, "c", 0),
                "yes"));
        // 根為單一葉節點（無群組）已由上面各案例涵蓋

        for (Case k : cases) {
            String event = "evt_cond_" + uniq();
            publish(condJourney(event, k.condition()));
            String profile = ulid();
            ingest(event, profile, k.props());
            String e = enrollmentOf(profile);
            assertThat(lastPort(e, "cond_1")).as(k.name()).isEqualTo(k.expectedPort());
            assertThat(status(e)).as(k.name() + " 走完").isEqualTo("COMPLETED");
            assertThat(visits(e, "yes".equals(k.expectedPort()) ? "end_yes" : "end_no"))
                    .as(k.name() + " 到達對應終點")
                    .isEqualTo(1);
            assertThat(visits(e, "yes".equals(k.expectedPort()) ? "end_no" : "end_yes"))
                    .as(k.name() + " 沒走另一邊")
                    .isZero();
        }
    }

    @Test
    void customerAttribute_usesProfileAttributesWithoutPrefix() throws Exception {
        String event = "evt_attr_" + uniq();
        publish(condJourney(event, leaf("member_tier", "EQ", "GOLD")));

        String gold = ulid();
        putProfile(gold, props("member_tier", "GOLD"));
        ingest(event, gold, props());
        assertThat(lastPort(enrollmentOf(gold), "cond_1")).isEqualTo("yes");

        String silver = ulid();
        putProfile(silver, props("member_tier", "SILVER"));
        ingest(event, silver, props());
        assertThat(lastPort(enrollmentOf(silver), "cond_1")).isEqualTo("no");

        String blank = ulid();
        ingest(event, blank, props());
        assertThat(lastPort(enrollmentOf(blank), "cond_1")).as("沒有該屬性").isEqualTo("no");
    }

    @Test
    void triggerProperties_areNotCustomerAttributes() throws Exception {
        // 不加前綴的鍵只看客戶屬性：事件帶 member_tier 但客戶沒有 -> no
        String event = "evt_root_" + uniq();
        publish(condJourney(event, leaf("member_tier", "EQ", "GOLD")));
        String p = ulid();
        ingest(event, p, props("member_tier", "GOLD"));
        assertThat(lastPort(enrollmentOf(p), "cond_1")).isEqualTo("no");
    }

    @Test
    void decisionReason_doesNotQuoteValues_evaluatedHoldsThem() throws Exception {
        String event = "evt_reason_" + uniq();
        publish(condJourney(event, leaf(f("amount"), "GT", 500)));
        String p = ulid();
        ingest(event, p, props("amount", 7391));
        String e = enrollmentOf(p);
        String reason = jdbc.queryForObject(
                "select decision_reason from node_trace where enrollment_id = ? and node_id = 'cond_1'",
                String.class,
                e);
        String evaluated = jdbc.queryForObject(
                "select evaluated::text from node_trace where enrollment_id = ? and node_id = 'cond_1'",
                String.class,
                e);
        assertThat(reason).isNotBlank().doesNotContain("7391");
        assertThat(reason.length()).isLessThanOrEqualTo(200);
        assertThat(evaluated).as("實際取到的值放在 evaluated").contains("7391");
    }
}
