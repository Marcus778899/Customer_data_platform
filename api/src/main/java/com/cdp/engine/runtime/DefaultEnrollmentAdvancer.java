package com.cdp.engine.runtime;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cdp.engine.StateStore;
import com.cdp.engine.model.EnrollmentState;
import com.cdp.engine.model.EnrollmentStatus;
import com.cdp.engine.model.EnrollmentTrace;
import com.cdp.engine.model.WakeCondition;
import com.cdp.engine.model.WakeSignal;
import com.cdp.engine.node.Effect;
import com.cdp.engine.node.NodeConfig;
import com.cdp.engine.node.NodeContext;
import com.cdp.engine.node.NodeResult;
import com.cdp.journey.JourneyDefinition;
import com.cdp.journey.JourneyDefinitionStore;
import com.cdp.journey.JourneyNodeDefinition;

/**
 * 推進迴圈（04 §6）。整個 advance 在同一個交易內（含連續推進的每一步 save）：任何一步失敗整批回滾，狀態、軌跡、outbox
 * 一起退回，下一輪從原本的等待狀態重來；NESTED 讓呼叫端（AdvanceLoop、EventDispatcher）能逐筆隔離失敗。
 */
@Service
public class DefaultEnrollmentAdvancer implements EnrollmentAdvancer {

    /** 單次連續推進的節點上限（04 §6）。 */
    static final int MAX_STEPS = 50;

    /** 找不到 open trace 時的 seq 哨兵：由 StateStore.save 接在既有軌跡之後（max(seq)+1）。 */
    public static final long APPEND_TRACE = 0;

    private static final Logger log = LoggerFactory.getLogger(DefaultEnrollmentAdvancer.class);

    private final StateStore stateStore;
    private final JourneyDefinitionStore journeys;
    private final NodeRegistry nodes;
    private final Clock clock;

    public DefaultEnrollmentAdvancer(
            StateStore stateStore,
            JourneyDefinitionStore journeys,
            NodeRegistry nodes,
            Clock clock) {
        this.stateStore = stateStore;
        this.journeys = journeys;
        this.nodes = nodes;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.NESTED)
    public EnrollmentState advance(EnrollmentState state, WakeSignal wakeSignal) {
        Instant now = clock.instant();
        EnrollmentState current = state;
        WakeSignal signal = wakeSignal;
        long seq = 1;
        Instant enteredAt = now;
        EnrollmentTrace open = null;
        if (signal != null) {
            // 等待節點被喚醒：沿用尚未離開的那列軌跡（07 §5 補完一次）
            open = stateStore.findOpenTrace(state.tenantId(), state.enrollmentId()).orElse(null);
            if (open != null) {
                seq = open.seq();
                enteredAt = open.enteredAt();
            } else {
                seq = APPEND_TRACE;
            }
        }
        JourneyDefinition journey =
                journeys.find(state.tenantId(), state.journeyId(), state.journeyVersion())
                        .orElse(null);
        if (journey == null) {
            log.error(
                    "journey definition missing enrollment_id={} journey_id={} v{}",
                    state.enrollmentId(),
                    state.journeyId(),
                    state.journeyVersion());
            return terminate(
                    current,
                    EnrollmentStatus.FAILED,
                    "旅程定義 " + state.journeyId() + " v" + state.journeyVersion() + " 已不存在，Enrollment 已中止。",
                    seq,
                    current.currentNodeId(),
                    open == null ? "Unknown" : open.nodeType(),
                    enteredAt,
                    now);
        }
        if (signal != null && open == null) {
            log.error("no open trace for waiting enrollment_id={}", state.enrollmentId());
            JourneyNodeDefinition at = journey.node(current.currentNodeId()).orElse(null);
            return terminate(
                    current,
                    EnrollmentStatus.FAILED,
                    "找不到等待節點 " + current.currentNodeId() + " 的軌跡，Enrollment 已中止。",
                    seq,
                    current.currentNodeId(),
                    at == null ? "Unknown" : at.type(),
                    enteredAt,
                    now);
        }

        for (int step = 0; step < MAX_STEPS; step++) {
            JourneyNodeDefinition def = journey.node(current.currentNodeId()).orElse(null);
            if (def == null) {
                return terminate(
                        current,
                        EnrollmentStatus.FAILED,
                        "旅程定義中找不到節點 " + current.currentNodeId() + "。",
                        seq,
                        current.currentNodeId(),
                        "Unknown",
                        enteredAt,
                        now);
            }

            NodeResult result;
            try {
                NodeContext context =
                        new NodeContext(
                                current.tenantId(),
                                current.enrollmentId(),
                                current.profileId(),
                                def.id(),
                                new NodeConfig(def.config()),
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                current.deliveryHistory(),
                                current.triggerSnapshot(),
                                current.variables(),
                                current.enrolledAt(),
                                signal,
                                now);
                result = nodes.get(def.type()).execute(context);
            } catch (RuntimeException e) {
                log.error(
                        "node execute failed enrollment_id={} node_id={}",
                        current.enrollmentId(),
                        def.id(),
                        e);
                return terminate(
                        current,
                        EnrollmentStatus.FAILED,
                        "節點 " + def.id() + " 執行失敗，Enrollment 已中止。",
                        seq,
                        def.id(),
                        def.type(),
                        enteredAt,
                        now);
            }

            List<Effect> effects = stamp(current.enrollmentId(), def.id(), seq, result.effects());
            String reason = reasonOf(result, def);
            Map<String, Object> evaluated =
                    result.evaluated() == null ? Map.of() : result.evaluated();
            Map<String, Object> variables = patch(current.variables(), result.variablePatch());

            switch (result.status()) {
                case ADVANCE -> {
                    String next = target(def, result.outPort());
                    if (next == null) {
                        return terminate(
                                current,
                                EnrollmentStatus.FAILED,
                                "節點 " + def.id() + " 沒有出口 " + result.outPort() + " 的連線。",
                                seq,
                                def.id(),
                                def.type(),
                                enteredAt,
                                now);
                    }
                    EnrollmentState moved =
                            copy(
                                    current,
                                    EnrollmentStatus.ACTIVE,
                                    next,
                                    null,
                                    variables,
                                    now,
                                    null,
                                    null);
                    stateStore.save(
                            moved,
                            new EnrollmentTrace(
                                    current.enrollmentId(),
                                    seq,
                                    def.id(),
                                    def.type(),
                                    enteredAt,
                                    now,
                                    result.outPort(),
                                    reason,
                                    evaluated),
                            effects);
                    current = moved;
                    seq++;
                    enteredAt = now;
                    signal = null;
                }
                case WAIT -> {
                    EnrollmentState waiting =
                            copy(
                                    current,
                                    EnrollmentStatus.WAITING,
                                    def.id(),
                                    result.wakeCondition(),
                                    variables,
                                    now,
                                    null,
                                    null);
                    stateStore.save(
                            waiting,
                            new EnrollmentTrace(
                                    current.enrollmentId(),
                                    seq,
                                    def.id(),
                                    def.type(),
                                    enteredAt,
                                    null,
                                    null,
                                    reason,
                                    evaluated),
                            effects);
                    return waiting;
                }
                case COMPLETE, EXIT, GOAL, FAIL -> {
                    EnrollmentStatus status =
                            switch (result.status()) {
                                case COMPLETE -> EnrollmentStatus.COMPLETED;
                                case EXIT -> EnrollmentStatus.EXITED;
                                case GOAL -> EnrollmentStatus.GOAL_MET;
                                default -> EnrollmentStatus.FAILED;
                            };
                    EnrollmentState ended =
                            copy(
                                    current,
                                    status,
                                    def.id(),
                                    null,
                                    variables,
                                    now,
                                    now,
                                    result.terminationReason());
                    stateStore.save(
                            ended,
                            new EnrollmentTrace(
                                    current.enrollmentId(),
                                    seq,
                                    def.id(),
                                    def.type(),
                                    enteredAt,
                                    now,
                                    null,
                                    reason,
                                    evaluated),
                            effects);
                    return ended;
                }
            }
        }

        // 超過連續推進上限：第二道防線（04 §6），轉 FAILED 並留下軌跡
        log.error(
                "advance exceeded {} steps enrollment_id={}", MAX_STEPS, current.enrollmentId());
        JourneyNodeDefinition at = journey.node(current.currentNodeId()).orElse(null);
        return terminate(
                current,
                EnrollmentStatus.FAILED,
                "單次連續推進超過 " + MAX_STEPS + " 個節點，Enrollment 已中止。",
                seq,
                current.currentNodeId(),
                at == null ? "Unknown" : at.type(),
                enteredAt,
                now);
    }

    private EnrollmentState terminate(
            EnrollmentState current,
            EnrollmentStatus status,
            String reason,
            long seq,
            String nodeId,
            String nodeType,
            Instant enteredAt,
            Instant now) {
        EnrollmentState ended =
                copy(current, status, nodeId, null, current.variables(), now, now, reason);
        stateStore.save(
                ended,
                new EnrollmentTrace(
                        current.enrollmentId(),
                        seq,
                        nodeId,
                        nodeType,
                        enteredAt,
                        now,
                        null,
                        reason,
                        Map.of()),
                List.of());
        return ended;
    }

    private static EnrollmentState copy(
            EnrollmentState s,
            EnrollmentStatus status,
            String nodeId,
            WakeCondition wake,
            Map<String, Object> variables,
            Instant now,
            Instant terminatedAt,
            String terminationReason) {
        return new EnrollmentState(
                s.enrollmentId(),
                s.tenantId(),
                s.journeyId(),
                s.journeyVersion(),
                s.profileId(),
                status,
                nodeId,
                wake,
                s.triggerSnapshot(),
                s.dedupKey(),
                variables,
                s.deliveryHistory(),
                s.enrolledAt(),
                now,
                terminatedAt,
                terminationReason);
    }

    /** 冪等鍵由引擎蓋章（04 §5）：節點一律回 null。 */
    private static List<Effect> stamp(
            String enrollmentId, String nodeId, long seq, List<Effect> effects) {
        if (effects == null || effects.isEmpty()) {
            return List.of();
        }
        List<Effect> stamped = new ArrayList<>(effects.size());
        for (int i = 0; i < effects.size(); i++) {
            stamped.add(
                    effects.get(i)
                            .withIdempotencyKey(IdempotencyKeys.of(enrollmentId, nodeId, seq, i)));
        }
        return stamped;
    }

    private static Map<String, Object> patch(
            Map<String, Object> variables, Map<String, Object> patch) {
        if (patch == null || patch.isEmpty()) {
            return variables;
        }
        Map<String, Object> merged = new LinkedHashMap<>(variables);
        merged.putAll(patch);
        return merged;
    }

    private static String reasonOf(NodeResult result, JourneyNodeDefinition def) {
        String reason = result.decisionReason();
        return reason == null || reason.isBlank() ? "節點 " + def.id() + " 已執行。" : reason;
    }

    /** outputs 的值為 String；StartNode 才會是 List（不會在這裡執行）。 */
    private static String target(JourneyNodeDefinition def, String outPort) {
        if (outPort == null || def.outputs() == null) {
            return null;
        }
        return def.outputs().get(outPort) instanceof String s ? s : null;
    }
}
