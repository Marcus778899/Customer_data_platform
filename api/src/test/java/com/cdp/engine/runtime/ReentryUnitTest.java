package com.cdp.engine.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.cdp.engine.DuplicateEnrollmentException;
import com.cdp.engine.StateStore;
import com.cdp.engine.model.EnrollmentState;
import com.cdp.engine.model.EnrollmentStatus;
import com.cdp.engine.model.TriggerSnapshot;
import com.cdp.journey.JourneyDefinition;
import com.cdp.journey.JourneyDefinitionStore;
import com.cdp.journey.JourneyNodeDefinition;
import com.cdp.journey.JourneyStatus;
import com.cdp.profile.ProfileReadStore;
import com.cdp.profile.ProfileRecord;

import tools.jackson.databind.json.JsonMapper;

class ReentryUnitTest {

    private static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");

    private static TriggerSnapshot trig(String eventId, Map<String, Object> props) {
        return new TriggerSnapshot(eventId, "e", T0, "CUSTOMER_BEHAVIOR", props, Map.of());
    }

    private static Map<String, Object> settings(String mode, String path, String cooldown) {
        Map<String, Object> r = new HashMap<>();
        r.put("mode", mode);
        if (path != null) r.put("dedup_key_path", path);
        if (cooldown != null) r.put("cooldown", cooldown);
        return Map.of("reentry", r);
    }

    // ---------- ReentryPolicy ----------

    @Test
    void rule_defaultsToUnlimited_andParsesFields() {
        assertThat(ReentryPolicy.rule(null).mode()).isEqualTo(ReentryPolicy.Mode.UNLIMITED);
        assertThat(ReentryPolicy.rule(Map.of()).mode()).isEqualTo(ReentryPolicy.Mode.UNLIMITED);
        assertThat(ReentryPolicy.rule(settings("BOGUS", null, null)).mode())
                .isEqualTo(ReentryPolicy.Mode.UNLIMITED);
        ReentryPolicy.Rule r = ReentryPolicy.rule(settings("DEDUP_KEY_WITH_COOLDOWN", "$.trigger.properties.a", "P1D"));
        assertThat(r.usesKey()).isTrue();
        assertThat(r.usesCooldown()).isTrue();
        assertThat(r.cooldown()).isEqualTo(Duration.ofDays(1));
        assertThat(ReentryPolicy.rule(settings("COOLDOWN", null, "nonsense")).cooldown()).isNull();
        assertThat(ReentryPolicy.rule(settings("ONCE", null, null)).usesKey()).isFalse();
    }

    @Test
    void extractKey_pathsAndEmptyValues() {
        Map<String, Object> props = new HashMap<>();
        props.put("cart_id", "C1");
        props.put("n", 5);
        props.put("empty", "");
        props.put("nil", null);
        props.put("items", List.of(Map.of("sku", "S0")));
        TriggerSnapshot t = trig("ev1", props);
        assertThat(ReentryPolicy.extractKey("$.trigger.properties.cart_id", t)).isEqualTo("C1");
        assertThat(ReentryPolicy.extractKey("$.trigger.properties.n", t)).isEqualTo("5");
        assertThat(ReentryPolicy.extractKey("$.trigger.properties.empty", t)).isNull();
        assertThat(ReentryPolicy.extractKey("$.trigger.properties.nil", t)).isNull();
        assertThat(ReentryPolicy.extractKey("$.trigger.properties.missing", t)).isNull();
        assertThat(ReentryPolicy.extractKey("$.trigger.properties.items[0].sku", t)).isEqualTo("S0");
        assertThat(ReentryPolicy.extractKey("$.trigger.properties.items[x]", t)).isNull();
        assertThat(ReentryPolicy.extractKey("$.trigger.properties.items[3]", t)).isNull();
        assertThat(ReentryPolicy.extractKey("$.trigger.event_id", t)).isEqualTo("ev1");
        assertThat(ReentryPolicy.extractKey("$.other.x", t)).isNull();
        assertThat(ReentryPolicy.extractKey("trigger.x", t)).isNull();
        assertThat(ReentryPolicy.extractKey(null, t)).isNull();
    }

    // ---------- TriggerSnapshotFactory ----------

    private final ProfileReadStore profiles = mock(ProfileReadStore.class);
    private final TriggerSnapshotFactory factory = new TriggerSnapshotFactory(new JsonMapper(), profiles);

    private static JourneyNodeDefinition condNode(Object condition) {
        return new JourneyNodeDefinition("c", "ConditionNode", null, Map.of("condition", condition), Map.of());
    }

    private static JourneyDefinition def(Map<String, Object> settings, JourneyNodeDefinition... nodes) {
        return new JourneyDefinition("j", 1, "n", null, JourneyStatus.ACTIVE, settings, "start", List.of(nodes), T0, T0);
    }

    private static List<Object> items(int n, int pad) {
        List<Object> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(Map.of("sku", "S" + i, "note", "x".repeat(pad)));
        return l;
    }

    @Test
    void profileSnapshot_onlyReferencedFields() {
        Map<String, Object> leaf = Map.of("type", "attribute", "field", "tier", "operator", "EQ", "value", "g");
        Map<String, Object> exit = Map.of("type", "attribute", "field", "opt_out", "operator", "EQ", "value", true);
        Map<String, Object> st = Map.of("global_exit", Map.of("conditions", List.of(exit, Map.of("type", "event", "event", "x"))));
        when(profiles.find("p"))
                .thenReturn(Optional.of(new ProfileRecord("p", Map.of("tier", "g", "opt_out", false, "city", "T"), T0, T0)));
        TriggerSnapshot out = factory.build(trig("e1", Map.of("a", 1)), "p", def(st, condNode(leaf)));
        assertThat(out.profileSnapshot()).containsOnlyKeys("tier", "opt_out");
        assertThat(factory.build(trig("e1", Map.of()), "p", def(Map.of())).profileSnapshot()).isEmpty();
        assertThat(factory.build(trig("e1", Map.of()), "p", null).profileSnapshot()).isEmpty();
    }

    @Test
    void truncation_arraysFirst20_thenWhitelist_thenReject() {
        JourneyDefinition d = def(settings("DEDUP_KEY", "$.trigger.properties.cart_id", null));
        Map<String, Object> small = new HashMap<>(Map.of("cart_id", "A", "items", items(30, 5)));
        assertThat(factory.build(trig("e", small), "p", d).properties().get("_truncated")).isNull();

        Map<String, Object> big = new HashMap<>(Map.of("cart_id", "A", "items", items(100, 1000)));
        Map<String, Object> cut = factory.build(trig("e", big), "p", d).properties();
        assertThat((List<?>) cut.get("items")).hasSize(20);
        assertThat(cut.get("_truncated")).isEqualTo(true);
        assertThat(cut.get("_total_count")).isEqualTo(100);
        assertThat(cut.get("cart_id")).isEqualTo("A");

        Map<String, Object> huge = new HashMap<>(Map.of("cart_id", "A", "items", items(100, 5000)));
        Map<String, Object> kept = factory.build(trig("e", huge), "p", d).properties();
        assertThat(kept).containsOnlyKeys("cart_id", "_truncated", "_total_count");

        Map<String, Object> monster = Map.of("cart_id", "x".repeat(70_000));
        assertThatThrownBy(() -> factory.build(trig("e", monster), "p", d))
                .isInstanceOf(SnapshotTooLargeException.class);
    }

    @Test
    void jsonbSize_countsSeparatorsOutsideStringsOnly() {
        assertThat(TriggerSnapshotFactory.jsonbSize("{\"a\":1,\"b\":\"x,:\\\"y\"}")).isEqualTo(20 + 3);
    }

    // ---------- EngineJourneyRuntime ----------

    private final StateStore store = mock(StateStore.class);
    private final EnrollmentAdvancer advancer = mock(EnrollmentAdvancer.class);
    private final EngineJourneyRuntime.ReentryStore reentry = mock(EngineJourneyRuntime.ReentryStore.class);
    private final JourneyDefinitionStore journeys = mock(JourneyDefinitionStore.class);
    private final TriggerSnapshotFactory snaps = mock(TriggerSnapshotFactory.class);
    private final EngineJourneyRuntime runtime =
            new EngineJourneyRuntime(
                    store, advancer, () -> "new-id", Clock.fixed(T0, ZoneOffset.UTC), reentry, journeys, snaps);

    private EnrollmentState existing(String id) {
        return new EnrollmentState(
                id, "j", 1, "p", EnrollmentStatus.WAITING, "n", null, trig("old", Map.of()), null, Map.of(),
                List.of(), T0, T0, null, null);
    }

    private void givenSettings(Map<String, Object> s) {
        when(journeys.find("j", 1)).thenReturn(Optional.of(def(s)));
        when(snaps.build(any(), any(), any())).thenAnswer(i -> i.getArgument(0));
        when(advancer.advance(any(), any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void start_dedupKeyMode_storesOnlyG3Key_notEventId() throws Exception {
        givenSettings(settings("DEDUP_KEY", "$.trigger.properties.cart_id", null));
        when(reentry.findByTriggerEvent("j", "ev")).thenReturn(Optional.empty());
        when(store.create(any())).thenAnswer(i -> i.getArgument(0));
        EnrollmentState s = runtime.start("j", 1, "src", "p", trig("ev", Map.of("cart_id", "C")), "ev");
        assertThat(s.dedupKey()).isEqualTo("C");
        verify(reentry, never()).lockProfile(any(), any());
    }

    @Test
    void start_onceAndUnlimitedAndMissingKey() throws Exception {
        when(store.create(any())).thenAnswer(i -> i.getArgument(0));
        givenSettings(settings("ONCE", null, null));
        assertThat(runtime.start("j", 1, "src", "p", trig("ev", Map.of()), "ev").dedupKey())
                .isEqualTo("__ONCE__");
        givenSettings(Map.of());
        assertThat(runtime.start("j", 1, "src", "p", trig("ev", Map.of()), "ev").dedupKey()).isNull();
        givenSettings(settings("DEDUP_KEY", "$.trigger.properties.cart_id", null));
        assertThat(runtime.start("j", 1, "src", "p", trig("ev", Map.of()), "ev").dedupKey()).isNull();
    }

    @Test
    void start_redeliveredEvent_returnsExistingWithoutCreating() throws Exception {
        givenSettings(Map.of());
        when(reentry.findByTriggerEvent("j", "ev")).thenReturn(Optional.of("old"));
        when(store.findById("old")).thenReturn(Optional.of(existing("old")));
        assertThat(runtime.start("j", 1, "src", "p", trig("ev", Map.of()), "ev").enrollmentId()).isEqualTo("old");
        verify(store, never()).create(any());
    }

    @Test
    void start_cooldownBlocked_locksAndReturnsBlocker_usingCutoff() throws Exception {
        givenSettings(settings("COOLDOWN", null, "PT1H"));
        when(reentry.findByTriggerEvent(any(), any())).thenReturn(Optional.empty());
        when(reentry.findCooldownBlocker("j", "p", T0.minus(Duration.ofHours(1)))).thenReturn(Optional.of("blk"));
        when(store.findById("blk")).thenReturn(Optional.of(existing("blk")));
        assertThat(runtime.start("j", 1, "src", "p", trig("ev", Map.of()), "ev").enrollmentId()).isEqualTo("blk");
        verify(reentry).lockProfile("j", "p");
        verify(store, never()).create(any());
    }

    @Test
    void start_cooldownFree_creates_andUniqueConflictReturnsExisting() throws Exception {
        givenSettings(settings("COOLDOWN", null, null));
        when(reentry.findByTriggerEvent(any(), any())).thenReturn(Optional.empty());
        when(reentry.findCooldownBlocker(any(), any(), any())).thenReturn(Optional.empty());
        when(store.create(any())).thenThrow(new DuplicateEnrollmentException("dup"));
        when(store.findById("dup")).thenReturn(Optional.of(existing("dup")));
        assertThat(runtime.start("j", 1, "src", "p", trig("ev", Map.of()), "ev").enrollmentId()).isEqualTo("dup");
        verify(advancer, never()).advance(any(), any());
    }

    @Test
    void start_missingDefinition_fallsBackToUnlimited() throws Exception {
        when(journeys.find("j", 1)).thenReturn(Optional.empty());
        when(snaps.build(any(), any(), any())).thenAnswer(i -> i.getArgument(0));
        when(advancer.advance(any(), any())).thenAnswer(i -> i.getArgument(0));
        when(store.create(any())).thenAnswer(i -> i.getArgument(0));
        assertThat(runtime.start("j", 1, "src", "p", trig("ev", Map.of()), "ev").dedupKey()).isNull();
    }
}
