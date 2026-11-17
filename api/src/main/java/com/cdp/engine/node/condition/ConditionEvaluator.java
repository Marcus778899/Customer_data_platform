package com.cdp.engine.node.condition;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * 條件樹求值（journeys.md 第 4 節「條件欄位的根」「比較語意」）。純函式、無狀態。
 *
 * <p>格式不合、型別不合一律不成立、不丟例外：資料是髒的，不能讓引擎因此中止。群組不做短路，讓 evaluated 帶出每個葉節點實際取到的值。
 */
public final class ConditionEvaluator {

    private static final int MAX_DEPTH = 10;
    private static final String TRIGGER_PREFIX = "trigger.properties.";
    private static final String EVENT_PREFIX = "properties.";

    private ConditionEvaluator() {}

    /** 求值結果；evaluated 的鍵為 field 的點號換成底線（snake_case），值為實際取到的值（可為 null）。 */
    public record Evaluation(boolean matched, Map<String, Object> evaluated) {}

    /** 求值。resolver 以 field 取值，欄位不存在回 null。 */
    public static Evaluation evaluate(Object condition, Function<String, Object> resolver) {
        Map<String, Object> evaluated = new LinkedHashMap<>();
        boolean matched = eval(condition, resolver, evaluated, 0);
        return new Evaluation(matched, evaluated);
    }

    /** 條件樹用到的全部葉節點 field（依出現順序、去重）。 */
    public static List<String> fieldsOf(Object condition) {
        Set<String> fields = new LinkedHashSet<>();
        collect(condition, fields, 0);
        return List.copyOf(fields);
    }

    /** 需要預查的客戶屬性鍵：葉節點 field 中不以 trigger.properties. 開頭者。 */
    public static Set<String> profileFieldsOf(Object condition) {
        Set<String> keys = new LinkedHashSet<>();
        for (String f : fieldsOf(condition)) {
            if (!f.startsWith(TRIGGER_PREFIX)) {
                keys.add(f);
            }
        }
        return keys;
    }

    /** ConditionNode／global_exit：旅程進度（客戶屬性不加前綴；觸發事件屬性為 trigger.properties.&lt;鍵&gt;）。 */
    public static Function<String, Object> progressResolver(
            Map<String, Object> profileFields, Map<String, Object> triggerProperties) {
        return field -> {
            if (field == null) {
                return null;
            }
            if (field.startsWith(TRIGGER_PREFIX)) {
                return triggerProperties == null
                        ? null
                        : triggerProperties.get(field.substring(TRIGGER_PREFIX.length()));
            }
            return profileFields == null ? null : profileFields.get(field);
        };
    }

    /** event_filter：正在比對的那一筆事件，field 為 properties.&lt;鍵&gt;，不可用客戶屬性。 */
    public static Function<String, Object> eventResolver(Map<String, Object> eventProperties) {
        return field -> {
            if (field == null || !field.startsWith(EVENT_PREFIX)) {
                return null;
            }
            return eventProperties == null
                    ? null
                    : eventProperties.get(field.substring(EVENT_PREFIX.length()));
        };
    }

    /** event_filter 的簡便入口：null = 不篩選（成立）。 */
    public static boolean eventMatches(Object filter, Map<String, Object> eventProperties) {
        if (filter == null) {
            return true;
        }
        return evaluate(filter, eventResolver(eventProperties)).matched();
    }

    private static boolean eval(
            Object node, Function<String, Object> resolver, Map<String, Object> evaluated, int depth) {
        if (!(node instanceof Map<?, ?> map) || depth > MAX_DEPTH) {
            return false;
        }
        if (map.get("op") instanceof String group) {
            return evalGroup(group, map.get("children"), resolver, evaluated, depth);
        }
        if ("attribute".equals(map.get("type")) && map.get("field") instanceof String field) {
            Object actual = resolver.apply(field);
            evaluated.put(field.replace('.', '_'), actual);
            return leaf(String.valueOf(map.get("operator")), actual, map.get("value"));
        }
        return false;
    }

    private static boolean evalGroup(
            String op,
            Object children,
            Function<String, Object> resolver,
            Map<String, Object> evaluated,
            int depth) {
        if (!(children instanceof List<?> list) || list.isEmpty()) {
            return false;
        }
        boolean all = true;
        boolean any = false;
        boolean first = false;
        for (int i = 0; i < list.size(); i++) {
            boolean r = eval(list.get(i), resolver, evaluated, depth + 1);
            all &= r;
            any |= r;
            if (i == 0) {
                first = r;
            }
        }
        return switch (op) {
            case "AND" -> all;
            case "OR" -> any;
            case "NOT" -> list.size() == 1 && !first;
            default -> false;
        };
    }

    private static boolean leaf(String operator, Object actual, Object expected) {
        if ("IS_NULL".equals(operator)) {
            return actual == null;
        }
        if ("IS_NOT_NULL".equals(operator)) {
            return actual != null;
        }
        if (actual == null) {
            return false; // 欄位不存在或為 null：其他運算子一律不成立（含 NEQ、NOT_IN）
        }
        return switch (operator) {
            case "EQ" -> jsonEquals(actual, expected);
            case "NEQ" -> !jsonEquals(actual, expected);
            case "IN" -> expected instanceof List<?> l && l.stream().anyMatch(v -> jsonEquals(actual, v));
            case "NOT_IN" ->
                    expected instanceof List<?> l && l.stream().noneMatch(v -> jsonEquals(actual, v));
            case "GT" -> numeric(actual, expected, c -> c > 0);
            case "GTE" -> numeric(actual, expected, c -> c >= 0);
            case "LT" -> numeric(actual, expected, c -> c < 0);
            case "LTE" -> numeric(actual, expected, c -> c <= 0);
            case "CONTAINS" -> contains(actual, expected);
            default -> false;
        };
    }

    /** 只在兩邊都是 number 時比較；其他（含字串、布林）= 不成立。 */
    private static boolean numeric(
            Object actual, Object expected, java.util.function.IntPredicate test) {
        if (!(actual instanceof Number) || !(expected instanceof Number)) {
            return false;
        }
        return test.test(decimal(actual).compareTo(decimal(expected)));
    }

    private static boolean contains(Object actual, Object expected) {
        if (actual instanceof String s) {
            return expected instanceof String e && s.contains(e);
        }
        if (actual instanceof List<?> l) {
            return l.stream().anyMatch(v -> jsonEquals(v, expected));
        }
        return false;
    }

    private static BigDecimal decimal(Object o) {
        return new BigDecimal(o.toString());
    }

    private static boolean jsonEquals(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Number && b instanceof Number) {
            return decimal(a).compareTo(decimal(b)) == 0;
        }
        if (a instanceof List<?> la && b instanceof List<?> lb) {
            if (la.size() != lb.size()) {
                return false;
            }
            for (int i = 0; i < la.size(); i++) {
                if (!jsonEquals(la.get(i), lb.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            if (ma.size() != mb.size()) {
                return false;
            }
            for (Map.Entry<?, ?> e : ma.entrySet()) {
                if (!mb.containsKey(e.getKey()) || !jsonEquals(e.getValue(), mb.get(e.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(a, b);
    }

    private static void collect(Object node, Set<String> fields, int depth) {
        if (!(node instanceof Map<?, ?> map) || depth > MAX_DEPTH) {
            return;
        }
        if (map.get("children") instanceof List<?> list) {
            for (Object child : list) {
                collect(child, fields, depth + 1);
            }
        } else if (map.get("field") instanceof String f) {
            fields.add(f);
        }
    }
}
