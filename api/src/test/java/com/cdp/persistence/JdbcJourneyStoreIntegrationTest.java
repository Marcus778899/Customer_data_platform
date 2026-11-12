package com.cdp.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.cdp.journey.JourneyStatus;
import com.cdp.journey.JourneyStore.JourneyRow;
import com.cdp.journey.JourneyStore.StoppedEnrollment;

import tools.jackson.databind.json.JsonMapper;

/** 真的 PostgreSQL：journey 草稿、發佈、停止、版本與 field-keys 查詢。 */
class JdbcJourneyStoreIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-01-02T00:00:00Z");

    private JdbcClient jdbc;
    private JdbcJourneyStore store;

    @BeforeEach
    void setUp() {
        jdbc = PgTestDb.fresh();
        store = new JdbcJourneyStore(jdbc, JsonMapper.builder().build());
    }

    private static Map<String, Object> def(String name) {
        return Map.of(
                "name", name,
                "description", "d",
                "settings", Map.of("max_visits", 1),
                "entry_node", "n1",
                "nodes", List.of(Map.of("id", "n1", "type", "end")));
    }

    @Test
    void insertDraftJourney_thenFindAndLock() {
        store.insertDraftJourney("j1", def("A"), "u1", T0);

        JourneyRow row = store.find("j1").orElseThrow();
        assertThat(row.status()).isEqualTo(JourneyStatus.DRAFT);
        assertThat(row.latestVersion()).isNull();
        assertThat(row.draft()).containsEntry("name", "A");
        assertThat(row.draftLayout()).isNull();
        assertThat(row.draftRevision()).isEqualTo(1);
        assertThat(row.draftUpdatedBy()).isEqualTo("u1");
        assertThat(row.createdAt()).isEqualTo(T0);
        assertThat(row.stoppedAt()).isNull();
        assertThat(store.lock("j1")).isPresent();
        assertThat(store.find("nope")).isEmpty();
        assertThat(store.lock("nope")).isEmpty();
    }

    @Test
    void saveDraft_updatesRevisionAndLayout_clearDraftResets() {
        store.insertDraftJourney("j1", def("A"), "u1", T0);

        store.saveDraft("j1", 2, def("B"), Map.of("n1", Map.of("x", 1)), "u2", T1);
        JourneyRow saved = store.find("j1").orElseThrow();
        assertThat(saved.draft()).containsEntry("name", "B");
        assertThat(saved.draftLayout()).containsKey("n1");
        assertThat(saved.draftRevision()).isEqualTo(2);
        assertThat(saved.draftUpdatedBy()).isEqualTo("u2");
        assertThat(saved.updatedAt()).isEqualTo(T1);

        store.saveDraft("j1", 3, def("C"), null, "u2", T1);
        assertThat(store.find("j1").orElseThrow().draftLayout()).isNull();

        store.clearDraft("j1", T1);
        JourneyRow cleared = store.find("j1").orElseThrow();
        assertThat(cleared.draft()).isNull();
        assertThat(cleared.draftRevision()).isZero();
        assertThat(cleared.draftUpdatedBy()).isNull();
        assertThat(cleared.draftUpdatedAt()).isNull();
    }

    @Test
    void insertVersion_incrementsAndStoresLayout_markPublished() {
        store.insertDraftJourney("j1", def("A"), "u1", T0);

        int v1 = store.insertVersion("j1", def("A"), Map.of("n1", Map.of("x", 1)), T0);
        int v2 = store.insertVersion("j1", def("A2"), null, T1);
        assertThat(v1).isEqualTo(1);
        assertThat(v2).isEqualTo(2);
        assertThat(store.versionExists("j1", 2)).isTrue();
        assertThat(store.versionExists("j1", 3)).isFalse();
        assertThat(store.findLayout("j1", 1)).isPresent();
        assertThat(store.findLayout("j1", 1).orElseThrow()).containsKey("n1");
        assertThat(store.findLayout("j1", 9)).isEmpty();

        store.markPublished("j1", 2, false, T1);
        JourneyRow keep = store.find("j1").orElseThrow();
        assertThat(keep.status()).isEqualTo(JourneyStatus.ACTIVE);
        assertThat(keep.latestVersion()).isEqualTo(2);
        assertThat(keep.draft()).isNotNull();

        store.markPublished("j1", 2, true, T1);
        JourneyRow cleared = store.find("j1").orElseThrow();
        assertThat(cleared.draft()).isNull();
        assertThat(cleared.draftRevision()).isZero();
    }

    @Test
    void insertPublished_andEnsureRowForExisting() {
        store.insertPublished("j2", 1, T0);
        JourneyRow row = store.find("j2").orElseThrow();
        assertThat(row.status()).isEqualTo(JourneyStatus.ACTIVE);
        assertThat(row.latestVersion()).isEqualTo(1);

        // 舊版旅程：只有 journey_definition，沒有 journey 列
        jdbc.sql(
                        "insert into journey_definition (journey_id, version, name, status, settings, entry_node,"
                                + " nodes, created_at) values ('old', 1, 'o', 'ACTIVE', '{}', 'n', '[]', now()),"
                                + " ('old', 2, 'o', 'ACTIVE', '{}', 'n', '[]', now())")
                .update();
        store.ensureRowForExisting("old", T1);
        JourneyRow old = store.find("old").orElseThrow();
        assertThat(old.status()).isEqualTo(JourneyStatus.ACTIVE);
        assertThat(old.latestVersion()).isEqualTo(2);
        // 已存在不動
        store.ensureRowForExisting("old", T0);
        assertThat(store.find("old").orElseThrow().updatedAt()).isEqualTo(T1);
    }

    @Test
    void markAborted_setsStopFieldsAndAbortsVersions() {
        store.insertDraftJourney("j1", def("A"), "u1", T0);
        store.insertVersion("j1", def("A"), null, T0);
        assertThat(store.isAborted("j1")).isFalse();

        store.markAborted("j1", "取消", "u9", T1);

        JourneyRow row = store.find("j1").orElseThrow();
        assertThat(row.status()).isEqualTo(JourneyStatus.ABORTED);
        assertThat(row.stoppedAt()).isEqualTo(T1);
        assertThat(row.stopReason()).isEqualTo("取消");
        assertThat(row.stoppedBy()).isEqualTo("u9");
        assertThat(store.isAborted("j1")).isTrue();
        assertThat(jdbc.sql("select status from journey_definition where journey_id = 'j1'")
                        .query(String.class)
                        .single())
                .isEqualTo("ABORTED");
    }

    @Test
    void lockOpenEnrollmentsOfStoppedJourneys_onlyOpenOnesOfAborted() {
        store.insertPublished("live", 1, T0);
        store.insertPublished("dead", 1, T0);
        store.markAborted("dead", "r", null, T1);
        enroll("e1", "dead", "WAITING");
        enroll("e2", "dead", "COMPLETED");
        enroll("e3", "live", "WAITING");

        List<StoppedEnrollment> rows = store.lockOpenEnrollmentsOfStoppedJourneys(10);

        assertThat(rows).containsExactly(new StoppedEnrollment("e1", "dead", 1, "r"));
        assertThat(store.lockOpenEnrollmentsOfStoppedJourneys(0)).isEmpty();
    }

    @Test
    void fieldKeys_unionOfProfileAttributesAndEventProperties() {
        jdbc.sql(
                        "insert into profile (profile_id, attributes, created_at, updated_at) values"
                                + " ('p1', '{\"name\":\"a\",\"city\":\"x\"}', now(), now()),"
                                + " ('p2', '{\"age\":1}', now(), now())")
                .update();
        event("ev1", "buy", "{\"amount\":1,\"sku\":\"s\"}", "2026-01-01T00:00:00Z");
        event("ev2", "buy", "[1,2]", "2026-01-02T00:00:00Z");
        event("ev3", "buy", null, "2026-01-03T00:00:00Z");
        event("ev4", "other", "{\"zzz\":1}", "2026-01-03T00:00:00Z");

        assertThat(store.profileAttributeKeys(10)).containsExactly("age", "city", "name");
        assertThat(store.eventPropertyKeys("buy", 10)).containsExactly("amount", "sku");
        assertThat(store.eventPropertyKeys("none", 10)).isEmpty();
    }

    private void enroll(String id, String journey, String status) {
        jdbc.sql(
                        "insert into enrollment_record (enrollment_id, journey_id, journey_version, profile_id,"
                                + " status, current_node_id, trigger_snapshot, enrolled_at, updated_at)"
                                + " values (:id, :j, 1, 'p', :s, 'n1', '{}', now(), now())")
                .param("id", id)
                .param("j", journey)
                .param("s", status)
                .update();
    }

    private void event(String id, String name, String props, String at) {
        jdbc.sql(
                        "insert into event (event_id, source_type, name, occurred_at, received_at, identity,"
                                + " properties) values (:id, 'test', :n, cast(:at as timestamptz), now(), '{}',"
                                + " cast(:p as jsonb))")
                .param("id", id)
                .param("n", name)
                .param("at", at)
                .param("p", props)
                .update();
    }
}
