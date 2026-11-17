package com.cdp.engine.runtime;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.cdp.engine.model.TriggerSnapshot;

/** settings.reentry 解讀與去重鍵抽取（spec 02 §6、journeys.md §4）。純函式。 */
public final class ReentryPolicy {

    public static final String ONCE_KEY = "__ONCE__";

    private ReentryPolicy() {}

    public enum Mode {
        ONCE,
        DEDUP_KEY,
        COOLDOWN,
        DEDUP_KEY_WITH_COOLDOWN,
        UNLIMITED
    }

    /** cooldown 為 null = 未設定或無法解析。 */
    public record Rule(Mode mode, String dedupKeyPath, Duration cooldown) {

        public boolean usesKey() {
            return mode == Mode.DEDUP_KEY || mode == Mode.DEDUP_KEY_WITH_COOLDOWN;
        }

        public boolean usesCooldown() {
            return mode == Mode.COOLDOWN || mode == Mode.DEDUP_KEY_WITH_COOLDOWN;
        }
    }

    /** 省略或無法辨識的 mode = UNLIMITED。 */
    public static Rule rule(Map<String, Object> settings) {
        if (settings == null || !(settings.get("reentry") instanceof Map<?, ?> r)) {
            return new Rule(Mode.UNLIMITED, null, null);
        }
        Mode mode = Mode.UNLIMITED;
        if (r.get("mode") instanceof String m) {
            try {
                mode = Mode.valueOf(m);
            } catch (IllegalArgumentException e) {
                mode = Mode.UNLIMITED;
            }
        }
        String path = r.get("dedup_key_path") instanceof String p ? p : null;
        Duration cooldown = null;
        if (r.get("cooldown") instanceof String c) {
            try {
                cooldown = Duration.parse(c);
            } catch (DateTimeParseException e) {
                cooldown = null;
            }
        }
        return new Rule(mode, path, cooldown);
    }

    /**
     * 依 {@code $.trigger.<…>} 路徑抽鍵；根為 trigger 的各欄位（event_id、event_name、occurred_at、source_type、
     * properties、profile_snapshot）。支援點號與 {@code [n]}。抽不到、null、空字串回 null。
     */
    public static String extractKey(String path, TriggerSnapshot trigger) {
        if (path == null || trigger == null || !path.startsWith("$.")) {
            return null;
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("event_id", trigger.eventId());
        root.put("event_name", trigger.eventName());
        root.put("occurred_at", trigger.occurredAt() == null ? null : trigger.occurredAt().toString());
        root.put("source_type", trigger.sourceType());
        root.put("properties", trigger.properties());
        root.put("profile_snapshot", trigger.profileSnapshot());
        String[] tokens = path.substring(2).split("\\.", -1);
        if (tokens.length < 2 || !"trigger".equals(tokens[0])) {
            return null;
        }
        Object cur = root;
        for (int i = 1; i < tokens.length && cur != null; i++) {
            cur = step(cur, tokens[i]);
        }
        if (cur instanceof String s) {
            return s.isEmpty() ? null : s;
        }
        if (cur instanceof Number || cur instanceof Boolean) {
            return cur.toString();
        }
        return null;
    }

    private static Object step(Object cur, String token) {
        String key = token;
        int idx = -1;
        int br = token.indexOf('[');
        if (br > 0 && token.endsWith("]")) {
            key = token.substring(0, br);
            try {
                idx = Integer.parseInt(token.substring(br + 1, token.length() - 1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        Object v = cur instanceof Map<?, ?> m ? m.get(key) : null;
        if (idx >= 0) {
            return v instanceof List<?> l && idx < l.size() ? l.get(idx) : null;
        }
        return v;
    }
}
