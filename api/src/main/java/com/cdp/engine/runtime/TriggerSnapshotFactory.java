package com.cdp.engine.runtime;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.cdp.engine.model.TriggerSnapshot;
import com.cdp.engine.node.condition.ConditionEvaluator;
import com.cdp.journey.JourneyDefinition;
import com.cdp.journey.JourneyNodeDefinition;
import com.cdp.profile.ProfileReadStore;
import com.cdp.profile.ProfileRecord;

import tools.jackson.databind.json.JsonMapper;

/**
 * G1 觸發快照的組裝與截斷（spec 02 §5）：profile_snapshot 只放旅程定義的 Condition 與 global_exit 引用的客戶屬性；超過
 * 64 KB 依序截斷（陣列前 20 筆並於 properties 頂層加 _truncated／_total_count → 只留定義引用的事件屬性 → 拒絕）。
 */
@Component
public class TriggerSnapshotFactory {

    public static final int LIMIT_BYTES = 64 * 1024;
    public static final int MAX_ARRAY = 20;
    private static final String TRIGGER_PREFIX = "trigger.properties.";
    private static final String DEDUP_PREFIX = "$.trigger.properties.";

    private final JsonMapper json;
    private final ProfileReadStore profiles;

    public TriggerSnapshotFactory(JsonMapper json, ProfileReadStore profiles) {
        this.json = json;
        this.profiles = profiles;
    }

    public TriggerSnapshot build(TriggerSnapshot raw, String profileId, JourneyDefinition definition) {
        Map<String, Object> props = raw.properties() == null ? Map.of() : raw.properties();
        TriggerSnapshot snap = with(raw, props, profileSnapshot(profileId, definition));
        if (size(snap) <= LIMIT_BYTES) {
            return snap;
        }
        int[] total = {0};
        boolean[] truncated = {false};
        Map<String, Object> cut = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : props.entrySet()) {
            cut.put(e.getKey(), truncate(e.getValue(), truncated, total));
        }
        if (truncated[0]) {
            cut.put("_truncated", true);
            cut.put("_total_count", total[0]);
        }
        snap = with(raw, cut, snap.profileSnapshot());
        if (size(snap) <= LIMIT_BYTES) {
            return snap;
        }
        Map<String, Object> kept = new LinkedHashMap<>();
        for (String k : referencedEventKeys(definition)) {
            if (cut.containsKey(k)) {
                kept.put(k, cut.get(k));
            }
        }
        if (truncated[0]) {
            kept.put("_truncated", true);
            kept.put("_total_count", total[0]);
        }
        snap = with(raw, kept, snap.profileSnapshot());
        if (size(snap) <= LIMIT_BYTES) {
            return snap;
        }
        throw new SnapshotTooLargeException(
                "trigger snapshot exceeds " + LIMIT_BYTES + " bytes after truncation event_id=" + raw.eventId());
    }

    private static TriggerSnapshot with(
            TriggerSnapshot raw, Map<String, Object> props, Map<String, Object> profile) {
        return new TriggerSnapshot(
                raw.eventId(), raw.eventName(), raw.occurredAt(), raw.sourceType(), props, profile);
    }

    private Map<String, Object> profileSnapshot(String profileId, JourneyDefinition definition) {
        Set<String> fields = referencedProfileFields(definition);
        if (fields.isEmpty() || profileId == null) {
            return Map.of();
        }
        Map<String, Object> attrs =
                profiles.find(profileId).map(ProfileRecord::attributes).orElse(Map.of());
        Map<String, Object> out = new LinkedHashMap<>();
        for (String f : fields) {
            if (attrs.get(f) != null) {
                out.put(f, attrs.get(f));
            }
        }
        return out;
    }

    /** Condition 節點與 global_exit 的 attribute 葉節點引用的客戶屬性鍵。 */
    static Set<String> referencedProfileFields(JourneyDefinition definition) {
        Set<String> out = new LinkedHashSet<>();
        for (Object cond : conditions(definition)) {
            out.addAll(ConditionEvaluator.profileFieldsOf(cond));
        }
        return out;
    }

    /** 同一批條件與去重路徑引用的事件屬性鍵（trigger.properties.&lt;鍵&gt;）。 */
    private static Set<String> referencedEventKeys(JourneyDefinition definition) {
        Set<String> out = new LinkedHashSet<>();
        for (Object cond : conditions(definition)) {
            for (String f : ConditionEvaluator.fieldsOf(cond)) {
                if (f.startsWith(TRIGGER_PREFIX)) {
                    out.add(firstSegment(f.substring(TRIGGER_PREFIX.length())));
                }
            }
        }
        if (definition != null) {
            ReentryPolicy.Rule rule = ReentryPolicy.rule(definition.settings());
            String p = rule.dedupKeyPath();
            if (rule.usesKey() && p != null && p.startsWith(DEDUP_PREFIX)) {
                out.add(firstSegment(p.substring(DEDUP_PREFIX.length())));
            }
        }
        return out;
    }

    private static String firstSegment(String s) {
        int cut = s.length();
        int dot = s.indexOf('.');
        int br = s.indexOf('[');
        if (dot >= 0) {
            cut = Math.min(cut, dot);
        }
        if (br >= 0) {
            cut = Math.min(cut, br);
        }
        return s.substring(0, cut);
    }

    private static List<Object> conditions(JourneyDefinition definition) {
        List<Object> out = new ArrayList<>();
        if (definition == null) {
            return out;
        }
        if (definition.nodes() != null) {
            for (JourneyNodeDefinition n : definition.nodes()) {
                if ("ConditionNode".equals(n.type())
                        && n.config() != null
                        && n.config().get("condition") != null) {
                    out.add(n.config().get("condition"));
                }
            }
        }
        Map<String, Object> settings = definition.settings();
        if (settings != null
                && settings.get("global_exit") instanceof Map<?, ?> g
                && g.get("conditions") instanceof List<?> list) {
            for (Object c : list) {
                if (c instanceof Map<?, ?> m && "attribute".equals(m.get("type"))) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    /** 陣列保留前 20 筆（遞迴）；total 記錄被截斷的陣列中最大的原始筆數。 */
    private static Object truncate(Object v, boolean[] truncated, int[] total) {
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            int n = Math.min(l.size(), MAX_ARRAY);
            for (int i = 0; i < n; i++) {
                out.add(truncate(l.get(i), truncated, total));
            }
            if (l.size() > MAX_ARRAY) {
                truncated[0] = true;
                total[0] = Math.max(total[0], l.size());
            }
            return out;
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), truncate(e.getValue(), truncated, total));
            }
            return out;
        }
        return v;
    }

    private int size(TriggerSnapshot s) {
        return jsonbSize(json.writeValueAsString(s));
    }

    /** 緊湊 JSON 存進 jsonb 後 ::text 會在字串外的 ',' 與 ':' 後各加一個空白；以 UTF-8 位元組數估到同一個數字。 */
    static int jsonbSize(String compact) {
        int extra = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < compact.length(); i++) {
            char c = compact.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == ',' || c == ':') {
                extra++;
            }
        }
        return compact.getBytes(StandardCharsets.UTF_8).length + extra;
    }
}
