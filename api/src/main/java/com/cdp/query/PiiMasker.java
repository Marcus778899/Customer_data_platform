package com.cdp.query;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.cdp.engine.model.TriggerSnapshot;

/**
 * 契約 §4.4：對 EnrollmentDetail 的 variables、trigger_snapshot.properties、trigger_snapshot.profile_snapshot、
 * trace[].evaluated 逐葉遮罩，並填 maskedPaths。所有角色都套用（v0.1.0 無解除遮罩權限）。
 */
@Component
public class PiiMasker {

    /** 引擎產生、不含個資的 evaluated 鍵，恆為明文（契約 §4.4）。 */
    public static final Set<String> ENGINE_KEYS =
            Set.of(
                    "event_id",
                    "event_name",
                    "duration",
                    "wake_at",
                    "template_id",
                    "channel_id",
                    "idempotency_key");

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+$");
    private static final String MASK = "***";

    private final MaskingProperties properties;

    public PiiMasker(MaskingProperties properties) {
        this.properties = properties;
    }

    /** 回傳遮罩後的新物件（maskedPaths 已填）；不修改輸入。 */
    public EnrollmentDetail mask(EnrollmentDetail raw) {
        Set<String> plain = properties.plainKeys() == null ? Set.of() : properties.plainKeys();
        Set<String> evaluatedPlain = new HashSet<>(plain);
        evaluatedPlain.addAll(ENGINE_KEYS);
        List<String> paths = new ArrayList<>();

        Map<String, Object> variables = map(raw.variables(), plain, "variables", paths);
        TriggerSnapshot t = raw.triggerSnapshot();
        TriggerSnapshot snapshot =
                t == null
                        ? null
                        : new TriggerSnapshot(
                                t.eventId(),
                                t.eventName(),
                                t.occurredAt(),
                                t.sourceType(),
                                map(t.properties(), plain, "trigger_snapshot.properties", paths),
                                map(t.profileSnapshot(), plain, "trigger_snapshot.profile_snapshot", paths));
        List<TraceEntry> trace = new ArrayList<>();
        for (int i = 0; i < raw.trace().size(); i++) {
            TraceEntry e = raw.trace().get(i);
            trace.add(
                    new TraceEntry(
                            e.seq(),
                            e.nodeId(),
                            e.nodeType(),
                            e.nodeName(),
                            e.enteredAt(),
                            e.exitedAt(),
                            e.outPort(),
                            e.decisionReason(),
                            map(e.evaluated(), evaluatedPlain, "trace[" + i + "].evaluated", paths)));
        }
        return new EnrollmentDetail(
                raw.enrollmentId(),
                raw.journeyId(),
                raw.journeyVersion(),
                raw.journeyName(),
                raw.profileId(),
                raw.status(),
                raw.currentNodeId(),
                raw.currentNodeName(),
                raw.currentNodeType(),
                raw.enrolledAt(),
                raw.updatedAt(),
                raw.terminatedAt(),
                raw.terminationReason(),
                raw.wakeCondition(),
                variables,
                snapshot,
                trace,
                raw.deliveryHistory(),
                raw.deliveryHistoryNextCursor(),
                raw.asOf(),
                paths);
    }

    private static Map<String, Object> map(
            Map<String, Object> in, Set<String> plain, String path, List<String> paths) {
        if (in == null) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            out.put(
                    e.getKey(),
                    plain.contains(e.getKey())
                            ? e.getValue()
                            : value(e.getValue(), plain, path + "." + e.getKey(), paths));
        }
        return out;
    }

    private static Object value(Object v, Set<String> plain, String path, List<String> paths) {
        if (v == null) {
            return null;
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> typed = new LinkedHashMap<>();
            m.forEach((k, x) -> typed.put(String.valueOf(k), x));
            return map(typed, plain, path, paths);
        }
        if (v instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                out.add(value(list.get(i), plain, path + "[" + i + "]", paths));
            }
            return out;
        }
        paths.add(path);
        if (v instanceof String s && EMAIL.matcher(s).matches()) {
            int first = s.offsetByCodePoints(0, 1);
            return s.substring(0, first) + "***@" + s.substring(s.indexOf('@') + 1);
        }
        return MASK;
    }
}
