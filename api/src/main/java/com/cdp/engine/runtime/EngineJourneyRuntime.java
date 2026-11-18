package com.cdp.engine.runtime;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cdp.common.IdGenerator;
import com.cdp.engine.DuplicateEnrollmentException;
import com.cdp.engine.JourneyRuntime;
import com.cdp.engine.StateStore;
import com.cdp.engine.model.EnrollmentState;
import com.cdp.engine.model.EnrollmentStatus;
import com.cdp.engine.model.TriggerSnapshot;
import com.cdp.engine.model.WakeSignal;
import com.cdp.journey.JourneyDefinition;
import com.cdp.journey.JourneyDefinitionStore;

/** 自建路線的 JourneyRuntime。cancel／cancelAll 於 v0.3.0（G10）實作，v0.1.0 丟 UnsupportedOperationException。 */
@Service
public class EngineJourneyRuntime implements JourneyRuntime {

    private static final Logger log = LoggerFactory.getLogger(EngineJourneyRuntime.class);

    /** 重新進入判斷用的查詢（spec 02 §6「落地」）。由 JdbcStateStore 實作；全部須在 start 的交易內呼叫。 */
    public interface ReentryStore {

        /** 取 pg_advisory_xact_lock（鍵 = journey_id＋profile_id），交易結束才釋放；同一客戶的併發觸發因此排隊。 */
        void lockProfile(String journeyId, String profileId);

        /** 同一事件（trigger_event_id）已建立的 Enrollment；事件重派發的冪等判斷。 */
        Optional<String> findByTriggerEvent(String journeyId, String triggerEventId);

        /** 冷卻期內擋人的 Enrollment：未結束，或 terminated_at 晚於 cutoff（= 現在 − 冷卻期）。取最近一筆。 */
        Optional<String> findCooldownBlocker(String journeyId, String profileId, Instant cutoff);
    }

    private final StateStore stateStore;
    private final EnrollmentAdvancer advancer;
    private final IdGenerator ids;
    private final Clock clock;
    private final ReentryStore reentry;
    private final JourneyDefinitionStore journeys;
    private final TriggerSnapshotFactory snapshots;

    public EngineJourneyRuntime(
            StateStore stateStore,
            EnrollmentAdvancer advancer,
            IdGenerator ids,
            Clock clock,
            ReentryStore reentry,
            JourneyDefinitionStore journeys,
            TriggerSnapshotFactory snapshots) {
        this.stateStore = stateStore;
        this.advancer = advancer;
        this.ids = ids;
        this.clock = clock;
        this.reentry = reentry;
        this.journeys = journeys;
        this.snapshots = snapshots;
    }

    /**
     * 重入判斷與建立（spec 02 §6「落地」），同一交易。呼叫端傳入的 dedupKey（事件派發傳 event_id）不使用：dedup_key 只放
     * G3 的值，事件重派發的冪等由 trigger_event_id 唯一索引承擔。被擋下或重複時回傳既有實例（不是錯誤）。NESTED：
     * 失敗只回滾這一筆，呼叫端可逐筆隔離。
     */
    @Override
    @Transactional(propagation = Propagation.NESTED)
    public EnrollmentState start(
            String journeyId,
            int version,
            String sourceNodeId,
            String profileId,
            TriggerSnapshot trigger,
            String dedupKey) {
        Instant now = clock.instant();
        JourneyDefinition definition = journeys.find(journeyId, version).orElse(null);
        ReentryPolicy.Rule rule =
                ReentryPolicy.rule(definition == null ? null : definition.settings());

        // 去重鍵取自完整事件（截斷之前）；抽不到 = UNLIMITED（寧可多發，不靜默擋人）
        ReentryPolicy.Mode mode = rule.mode();
        String g3Key = null;
        if (rule.usesKey()) {
            g3Key = ReentryPolicy.extractKey(rule.dedupKeyPath(), trigger);
            if (g3Key == null) {
                log.warn(
                        "dedup key not found, treated as UNLIMITED journey_id={} event_id={}",
                        journeyId,
                        trigger.eventId());
                mode = ReentryPolicy.Mode.UNLIMITED;
            }
        }
        boolean cooldown =
                mode == ReentryPolicy.Mode.COOLDOWN
                        || mode == ReentryPolicy.Mode.DEDUP_KEY_WITH_COOLDOWN;
        String g3 = null;
        if (mode == ReentryPolicy.Mode.ONCE) {
            g3 = ReentryPolicy.ONCE_KEY;
        } else if (mode == ReentryPolicy.Mode.DEDUP_KEY
                || mode == ReentryPolicy.Mode.DEDUP_KEY_WITH_COOLDOWN) {
            g3 = g3Key;
        }

        if (cooldown) {
            reentry.lockProfile(journeyId, profileId);
        }
        if (trigger.eventId() != null) {
            Optional<String> same = reentry.findByTriggerEvent(journeyId, trigger.eventId());
            if (same.isPresent()) {
                return existing(same.get());
            }
        }
        if (cooldown) {
            Duration cd = rule.cooldown() == null ? Duration.ZERO : rule.cooldown();
            Instant cutoff;
            try {
                cutoff = now.minus(cd);
                if (cutoff.isBefore(Instant.EPOCH)) {
                    cutoff = Instant.EPOCH;
                }
            } catch (ArithmeticException | DateTimeException e) {
                cutoff = Instant.EPOCH;
            }
            Optional<String> blocker = reentry.findCooldownBlocker(journeyId, profileId, cutoff);
            if (blocker.isPresent()) {
                log.info(
                        "reentry blocked by cooldown journey_id={} profile_id={} event_id={}",
                        journeyId,
                        profileId,
                        trigger.eventId());
                return existing(blocker.get());
            }
        }

        TriggerSnapshot snapshot = snapshots.build(trigger, profileId, definition);
        EnrollmentState created =
                new EnrollmentState(
                        ids.newId(),
                        journeyId,
                        version,
                        profileId,
                        EnrollmentStatus.ACTIVE,
                        sourceNodeId,
                        null,
                        snapshot,
                        g3,
                        Map.of(),
                        List.of(),
                        now,
                        now,
                        null,
                        null);
        try {
            created = stateStore.create(created);
        } catch (DuplicateEnrollmentException e) {
            log.info(
                    "reentry blocked by unique key journey_id={} profile_id={} event_id={}",
                    journeyId,
                    profileId,
                    trigger.eventId());
            return existing(e.existingEnrollmentId());
        }
        return advancer.advance(created, null);
    }

    private EnrollmentState existing(String enrollmentId) {
        return stateStore
                .findById(enrollmentId)
                .orElseThrow(() -> new IllegalStateException("existing enrollment vanished"));
    }

    @Override
    @Transactional
    public void signal(String enrollmentId, WakeSignal signal) {
        stateStore
                .findById(enrollmentId)
                .filter(s -> s.status() == EnrollmentStatus.WAITING)
                .ifPresent(s -> advancer.advance(s, signal));
    }

    @Override
    public void cancel(String enrollmentId, String reason) {
        throw new UnsupportedOperationException("v0.3.0 (G10)");
    }

    @Override
    public void cancelAll(String journeyId, String reason) {
        throw new UnsupportedOperationException("v0.3.0 (G10)");
    }

    @Override
    public Optional<EnrollmentState> get(String enrollmentId) {
        return stateStore.findById(enrollmentId);
    }
}
