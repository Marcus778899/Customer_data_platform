package com.cdp.journey;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * 旅程定義驗證（journeys.md §3、§4；spec 03 §6 規則 1～9、12～15；規則 10、11 本版不執行）。
 *
 * <p>輸入是草稿的 definition（name、description、settings、entry_node、nodes，JSON 解析後的 Map／List）。
 * 純函式、不碰資料庫；存檔只驗結構（由 {@link JourneyDraftService} 做），送審與核准時跑這裡全部規則。
 * 支援 §4 全部節點型別（執行由引擎負責）。
 */
@Component
public class JourneyGraphValidator {

    static final Pattern NODE_ID = Pattern.compile("^[a-z][a-z0-9_]*$");
    static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9_]*$");
    private static final Pattern MESSAGE_ID = Pattern.compile("^[A-Za-z0-9_.:-]+$");
    private static final int MAX_ID = 64;
    private static final int MAX_NAME = 100;
    private static final int MAX_DEPTH = 5;
    private static final Set<String> TERMINALS = Set.of("EndNode", "ExitNode", "GoalNode");
    private static final Set<String> WAITS = Set.of("WaitDelayNode", "WaitForEventNode");
    private static final Set<String> OPERATORS =
            Set.of("EQ", "NEQ", "IN", "NOT_IN", "GT", "GTE", "LT", "LTE", "CONTAINS", "IS_NULL", "IS_NOT_NULL");
    private static final Set<String> NO_VALUE = Set.of("IS_NULL", "IS_NOT_NULL");
    private static final Set<String> LIST_VALUE = Set.of("IN", "NOT_IN");
    private static final Set<String> REENTRY_MODES =
            Set.of("ONCE", "DEDUP_KEY", "COOLDOWN", "DEDUP_KEY_WITH_COOLDOWN", "UNLIMITED");

    /** 各型別的出口：必備 / 選填（WaitForEventNode、SplitTestNode 的出口由設定決定，另外處理）。 */
    private static final Map<String, List<String>> REQUIRED_PORTS =
            Map.of(
                    "StartNode", List.of("next"),
                    "EventSourceNode", List.of("next"),
                    "WaitDelayNode", List.of("next"),
                    "SendMessageNode", List.of("sent"),
                    "MergeNode", List.of("next"),
                    "ConditionNode", List.of("yes", "no"),
                    "EndNode", List.of(),
                    "ExitNode", List.of(),
                    "GoalNode", List.of());

    private static final Set<String> TYPES;

    static {
        Set<String> all = new HashSet<>(REQUIRED_PORTS.keySet());
        all.add("WaitForEventNode");
        all.add("SplitTestNode");
        TYPES = Set.copyOf(all);
    }

    /** 回傳驗證報告（errors 為空 = valid）。 */
    public ValidationReport validate(Map<String, Object> definition) {
        Run run = new Run(definition);
        run.execute();
        return new ValidationReport(run.errors.isEmpty(), List.copyOf(run.errors), List.copyOf(run.warnings));
    }

    /** 核准建立版本前整理內容：訊息節點的 template_id、channel_id 去前後空白（契約 §4）。回傳新的 Map，不改輸入。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> normalize(Map<String, Object> definition) {
        Map<String, Object> out = new LinkedHashMap<>(definition);
        if (!(definition.get("nodes") instanceof List<?> nodes)) {
            return out;
        }
        List<Object> copied = new ArrayList<>(nodes.size());
        for (Object o : nodes) {
            if (o instanceof Map<?, ?> m && "SendMessageNode".equals(m.get("type"))
                    && m.get("config") instanceof Map<?, ?> cfg) {
                Map<String, Object> node = new LinkedHashMap<>((Map<String, Object>) m);
                Map<String, Object> config = new LinkedHashMap<>((Map<String, Object>) cfg);
                for (String k : List.of("template_id", "channel_id")) {
                    if (config.get(k) instanceof String s) {
                        config.put(k, s.strip());
                    }
                }
                node.put("config", config);
                copied.add(node);
            } else {
                copied.add(o);
            }
        }
        out.put("nodes", copied);
        return out;
    }

    /** 一次驗證的工作區。 */
    private static final class Run {
        private final Map<String, Object> def;
        private final List<Issue> errors = new ArrayList<>();
        private final List<Issue> warnings = new ArrayList<>();
        private final List<Map<String, Object>> nodes = new ArrayList<>();
        private final List<String> ids = new ArrayList<>();
        private final Map<String, Integer> indexById = new HashMap<>();
        /** 節點 index → 出口目標 index（已存在者）。 */
        private final Map<Integer, List<Integer>> edges = new HashMap<>();
        /** 自己的出口有問題的節點：規則 6 把它們當作終點，避免一個斷線造成整串錯誤。 */
        private final Set<Integer> brokenOutputs = new HashSet<>();

        Run(Map<String, Object> def) {
            this.def = def;
        }

        void execute() {
            checkHeader();
            if (!collectNodes()) {
                return;
            }
            for (int i = 0; i < nodes.size(); i++) {
                if (nodes.get(i) != null) {
                    checkNode(i);
                }
            }
            checkStart();
            checkSources();
            checkReachability();
            checkLoops();
            checkSettings();
            checkWarnings();
        }

        // ---------- 基本欄位 ----------

        private void checkHeader() {
            Object name = def.get("name");
            if (!(name instanceof String s) || s.isBlank()) {
                err("definition.name", "旅程名稱必填。", null, null);
            } else if (s.length() > MAX_NAME) {
                err("definition.name", "旅程名稱不可超過 " + MAX_NAME + " 字。", null, null);
            }
            Object description = def.get("description");
            if (description != null && !(description instanceof String)) {
                err("definition.description", "說明須為文字。", null, null);
            }
            Object settings = def.get("settings");
            if (settings != null && !(settings instanceof Map)) {
                err("definition.settings", "設定須為物件。", null, null);
            }
        }

        private boolean collectNodes() {
            Object raw = def.get("nodes");
            if (!(raw instanceof List<?> list)) {
                err("definition.nodes", "節點清單必填。", null, null);
                return false;
            }
            for (int i = 0; i < list.size(); i++) {
                Object o = list.get(i);
                String base = "definition.nodes[" + i + "]";
                nodes.add(null);
                ids.add(null);
                if (!(o instanceof Map<?, ?> m)) {
                    err(base, "節點須為物件。", null, null);
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> node = (Map<String, Object>) m;
                Object id = node.get("id");
                if (!(id instanceof String sid) || sid.isEmpty()) {
                    err(base + ".id", "節點 id 必填。", null, null);
                    continue;
                }
                nodes.set(i, node);
                ids.set(i, sid);
                if (sid.length() > MAX_ID || !NODE_ID.matcher(sid).matches()) {
                    err(base + ".id", "節點 id「" + sid + "」須以小寫字母開頭，只含小寫字母、數字、底線，且不超過 " + MAX_ID + " 字。", null, sid);
                }
                if (indexById.putIfAbsent(sid, i) != null) {
                    err(base + ".id", "節點 id「" + sid + "」重複。", null, sid);
                }
                Object type = node.get("type");
                if (!(type instanceof String t) || !TYPES.contains(t)) {
                    err(base + ".type", "不支援的節點型別。", null, sid);
                    nodes.set(i, null);
                    continue;
                }
                if (node.get("name") != null && !(node.get("name") instanceof String)) {
                    err(base + ".name", "節點名稱須為文字。", null, sid);
                }
                if (node.get("config") != null && !(node.get("config") instanceof Map)) {
                    err(base + ".config", "config 須為物件。", null, sid);
                }
                if (node.get("outputs") != null && !(node.get("outputs") instanceof Map)) {
                    err(base + ".outputs", "outputs 須為物件。", null, sid);
                }
            }
            return true;
        }

        // ---------- 單一節點 ----------

        @SuppressWarnings("unchecked")
        private void checkNode(int i) {
            Map<String, Object> node = nodes.get(i);
            String type = (String) node.get("type");
            String id = ids.get(i);
            String base = "definition.nodes[" + i + "]";
            Map<String, Object> config =
                    node.get("config") instanceof Map<?, ?> c ? (Map<String, Object>) c : Map.of();
            Map<String, Object> outputs =
                    node.get("outputs") instanceof Map<?, ?> o ? (Map<String, Object>) o : Map.of();
            Set<String> allowedPorts = new HashSet<>();
            List<String> requiredPorts = new ArrayList<>();

            switch (type) {
                case "EventSourceNode" -> {
                    requireString(config, "event_name", base + ".config.event_name", "事件名稱必填。", id);
                    if (config.get("profile_filter") != null) {
                        err(base + ".config.profile_filter", "本版不支援客戶篩選（profile_filter）。", null, id);
                    }
                    checkEventFilter(config.get("event_filter"), base + ".config.event_filter", id);
                }
                case "WaitDelayNode" -> checkDuration(config.get("duration"), base + ".config.duration", id, true);
                case "SendMessageNode" -> {
                    checkMessageId(config.get("template_id"), base + ".config.template_id", "範本 ID", id);
                    checkMessageId(config.get("channel_id"), base + ".config.channel_id", "管道 ID", id);
                    allowedPorts.add("skipped");
                }
                case "WaitForEventNode" -> {
                    requireString(config, "event_name", base + ".config.event_name", "等待的事件名稱必填。", id);
                    Object timeout = config.get("timeout");
                    if (timeout != null) {
                        checkDuration(timeout, base + ".config.timeout", id, true);
                        requiredPorts.add("timeout");
                    }
                    requiredPorts.add("occurred");
                    checkEventFilter(config.get("event_filter"), base + ".config.event_filter", id);
                }
                case "ConditionNode" -> {
                    if (!config.containsKey("condition") || config.get("condition") == null) {
                        err(base + ".config.condition", "條件必填。", null, id);
                    } else {
                        checkCondition(config.get("condition"), base + ".config.condition", id, 1, false);
                    }
                }
                case "SplitTestNode" -> requiredPorts.addAll(checkSplit(config, base, id));
                case "ExitNode" -> {
                    Object reason = config.get("reason");
                    if (!(reason instanceof String r) || r.isBlank() || r.length() > 200) {
                        err(base + ".config.reason", "結束原因必填，1 到 200 字。", null, id);
                    }
                }
                case "GoalNode" -> requireString(config, "goal_id", base + ".config.goal_id", "目標 ID 必填。", id);
                default -> {
                    // StartNode、MergeNode、EndNode：沒有必填設定
                }
            }
            if (REQUIRED_PORTS.containsKey(type)) {
                requiredPorts.addAll(0, REQUIRED_PORTS.get(type));
            }
            allowedPorts.addAll(requiredPorts);
            checkOutputs(i, type, id, base, outputs, requiredPorts, allowedPorts);
        }

        private void checkOutputs(
                int i,
                String type,
                String id,
                String base,
                Map<String, Object> outputs,
                List<String> required,
                Set<String> allowed) {
            String label = labelOf(i);
            List<Integer> targets = new ArrayList<>();
            for (String port : required) {
                if (!outputs.containsKey(port) || outputs.get(port) == null) {
                    err(base + ".outputs." + port, "「" + label + "」的「" + port + "」出口還沒有連線。", 4, id);
                    brokenOutputs.add(i);
                }
            }
            for (Map.Entry<String, Object> e : outputs.entrySet()) {
                String port = e.getKey();
                String field = base + ".outputs." + port;
                Object value = e.getValue();
                if (value == null) {
                    continue;
                }
                if (!allowed.contains(port)) {
                    err(field, "「" + label + "」沒有「" + port + "」這個出口。", null, id);
                    brokenOutputs.add(i);
                    continue;
                }
                if (value instanceof List<?> l && "StartNode".equals(type)) {
                    for (Object o : l) {
                        addTarget(i, field, o, targets, id);
                    }
                } else if (value instanceof String) {
                    addTarget(i, field, value, targets, id);
                } else {
                    err(field, "出口的目標須為節點 id 字串。", 14, id);
                    brokenOutputs.add(i);
                }
            }
            edges.put(i, targets);
        }

        private void addTarget(int i, String field, Object value, List<Integer> targets, String id) {
            if (!(value instanceof String target) || target.isBlank()) {
                err(field, "出口的目標須為節點 id 字串。", 14, id);
                brokenOutputs.add(i);
                return;
            }
            Integer idx = indexById.get(target);
            if (idx == null || nodes.get(idx) == null) {
                err(field, "目標節點「" + target + "」不存在。", 3, id);
                brokenOutputs.add(i);
                return;
            }
            targets.add(idx);
        }

        private List<String> checkSplit(Map<String, Object> config, String base, String id) {
            List<String> ports = new ArrayList<>();
            String field = base + ".config.variants";
            if (!(config.get("variants") instanceof List<?> variants) || variants.size() < 2 || variants.size() > 10) {
                err(field, "分流版本需 2 到 10 個。", 8, id);
                return ports;
            }
            long total = 0;
            int controls = 0;
            Set<String> seen = new HashSet<>();
            boolean sumable = true;
            for (int v = 0; v < variants.size(); v++) {
                String vf = field + "[" + v + "]";
                if (!(variants.get(v) instanceof Map<?, ?> vm)) {
                    err(vf, "版本須為物件。", null, id);
                    sumable = false;
                    continue;
                }
                Object port = vm.get("port");
                if (!(port instanceof String p) || !KEY.matcher(p).matches() || p.length() > MAX_ID) {
                    err(vf + ".port", "版本的出口名須以小寫字母開頭，只含小寫字母、數字、底線。", null, id);
                } else if (!seen.add(p)) {
                    err(vf + ".port", "版本的出口名「" + p + "」重複。", null, id);
                } else {
                    ports.add(p);
                }
                Object weight = vm.get("weight");
                if (weight instanceof Integer w && w > 0) {
                    total += w;
                } else {
                    err(vf + ".weight", "版本的權重須為正整數。", 8, id);
                    sumable = false;
                }
                if (!(vm.get("label") instanceof String l) || l.isBlank()) {
                    err(vf + ".label", "版本的名稱必填。", null, id);
                }
                if (Boolean.TRUE.equals(vm.get("is_control"))) {
                    controls++;
                }
            }
            if (sumable && total != 100) {
                err(field, "各版本權重總和須為 100，目前是 " + total + "。", 8, id);
            }
            if (controls > 1) {
                err(field, "對照組（is_control）至多一個。", null, id);
            }
            Object seed = config.get("assignment_seed");
            if (seed != null && !"STABLE".equals(seed)) {
                err(base + ".config.assignment_seed", "分流種子只支援 STABLE。", null, id);
            }
            return ports;
        }

        // ---------- 條件 ----------

        private void checkEventFilter(Object filter, String field, String nodeId) {
            if (filter == null) {
                return;
            }
            checkCondition(filter, field, nodeId, 1, true);
        }

        private void checkCondition(Object cond, String field, String nodeId, int depth, boolean eventFilter) {
            if (!(cond instanceof Map<?, ?> m)) {
                err(field, "條件須為物件。", null, nodeId);
                return;
            }
            if (m.containsKey("op")) {
                Object op = m.get("op");
                if (!("AND".equals(op) || "OR".equals(op) || "NOT".equals(op))) {
                    err(field + ".op", "群組的 op 只支援 AND、OR、NOT。", null, nodeId);
                    return;
                }
                if (depth > MAX_DEPTH) {
                    err(field, "條件群組最多 " + MAX_DEPTH + " 層。", null, nodeId);
                    return;
                }
                if (!(m.get("children") instanceof List<?> children) || children.isEmpty()) {
                    err(field + ".children", "群組至少要有一個條件。", null, nodeId);
                    return;
                }
                if ("NOT".equals(op) && children.size() != 1) {
                    err(field + ".children", "NOT 恰好要有一個子條件。", null, nodeId);
                }
                for (int c = 0; c < children.size(); c++) {
                    checkCondition(children.get(c), field + ".children[" + c + "]", nodeId, depth + 1, eventFilter);
                }
                return;
            }
            if (!"attribute".equals(m.get("type"))) {
                err(field + ".type", "本版條件只支援屬性比較（attribute）。", null, nodeId);
                return;
            }
            Object f = m.get("field");
            if (!(f instanceof String fs) || !validConditionField(fs, eventFilter)) {
                err(
                        field + ".field",
                        eventFilter
                                ? "事件篩選的欄位須為 properties.<鍵>。"
                                : "欄位須為客戶屬性鍵，或 trigger.properties.<鍵>。",
                        null,
                        nodeId);
            }
            Object operator = m.get("operator");
            if (!(operator instanceof String os) || !OPERATORS.contains(os)) {
                err(field + ".operator", "不支援的比較運算子。", null, nodeId);
                return;
            }
            Object value = m.get("value");
            if (LIST_VALUE.contains(os)) {
                if (!(value instanceof List<?> l) || l.isEmpty()) {
                    err(field + ".value", "「" + os + "」的值須為非空清單。", null, nodeId);
                }
            } else if (!NO_VALUE.contains(os) && value == null) {
                err(field + ".value", "比較值必填。", null, nodeId);
            }
        }

        private static boolean validConditionField(String f, boolean eventFilter) {
            if (eventFilter) {
                return f.startsWith("properties.") && KEY.matcher(f.substring("properties.".length())).matches();
            }
            String key = f.startsWith("trigger.properties.") ? f.substring("trigger.properties.".length()) : f;
            return KEY.matcher(key).matches();
        }

        // ---------- 通用欄位 ----------

        private void requireString(Map<String, Object> config, String key, String field, String message, String id) {
            if (!(config.get(key) instanceof String s) || s.isBlank()) {
                err(field, message, null, id);
            }
        }

        private void checkDuration(Object value, String field, String id, boolean positive) {
            if (!(value instanceof String s) || s.isBlank()) {
                err(field, "期間必填（ISO-8601，例如 PT1H、P3D）。", null, id);
                return;
            }
            try {
                if (positive && Duration.parse(s).compareTo(Duration.ZERO) <= 0) {
                    err(field, "期間須為正值。", null, id);
                }
            } catch (DateTimeParseException e) {
                err(field, "期間格式不正確（ISO-8601，例如 PT1H、P3D）。", null, id);
            }
        }

        private void checkMessageId(Object value, String field, String what, String id) {
            if (!(value instanceof String s)) {
                err(field, what + "必填。", null, id);
                return;
            }
            String t = s.strip();
            if (t.isEmpty() || t.length() > 100 || !MESSAGE_ID.matcher(t).matches()) {
                err(field, what + "須為 1 到 100 字，只含英數字與 _ . : -。", null, id);
            }
        }

        private String labelOf(int i) {
            Object name = nodes.get(i).get("name");
            return name instanceof String s && !s.isBlank() ? s : ids.get(i);
        }

        // ---------- 圖 ----------

        private void checkStart() {
            List<Integer> starts = new ArrayList<>();
            for (int i = 0; i < nodes.size(); i++) {
                if (nodes.get(i) != null && "StartNode".equals(nodes.get(i).get("type"))) {
                    starts.add(i);
                }
            }
            if (starts.size() != 1) {
                err("definition.nodes", "旅程須有且只有一個起點（StartNode）。", 1, null);
                return;
            }
            String startId = ids.get(starts.get(0));
            if (!startId.equals(def.get("entry_node"))) {
                err("definition.entry_node", "進入節點（entry_node）須是起點「" + startId + "」。", 1, null);
            }
        }

        private void checkSources() {
            boolean anySource = false;
            for (int i = 0; i < nodes.size(); i++) {
                Map<String, Object> n = nodes.get(i);
                if (n == null) {
                    continue;
                }
                String type = (String) n.get("type");
                if ("EventSourceNode".equals(type)) {
                    anySource = true;
                }
                if ("StartNode".equals(type)) {
                    for (int t : edges.getOrDefault(i, List.of())) {
                        if (!"EventSourceNode".equals(nodes.get(t).get("type"))) {
                            err(
                                    "definition.nodes[" + i + "].outputs.next",
                                    "起點之後只能接事件來源，「" + labelOf(t) + "」不是。",
                                    2,
                                    ids.get(i));
                        }
                    }
                } else {
                    for (int t : edges.getOrDefault(i, List.of())) {
                        if ("EventSourceNode".equals(nodes.get(t).get("type"))) {
                            err(
                                    "definition.nodes[" + t + "]",
                                    "事件來源「" + labelOf(t) + "」只能直接接在起點之後。",
                                    2,
                                    ids.get(t));
                        }
                    }
                }
            }
            if (!anySource) {
                err("definition.nodes", "旅程需要至少一個事件來源（EventSourceNode）。", null, null);
            }
        }

        private void checkReachability() {
            Integer start = null;
            for (int i = 0; i < nodes.size(); i++) {
                if (nodes.get(i) != null && "StartNode".equals(nodes.get(i).get("type"))) {
                    if (start != null) {
                        return; // 起點不唯一，規則 1 已報
                    }
                    start = i;
                }
            }
            if (start == null) {
                return;
            }
            Set<Integer> reached = bfs(start, edges);
            for (int i = 0; i < nodes.size(); i++) {
                if (nodes.get(i) != null && !reached.contains(i)) {
                    err("definition.nodes[" + i + "]", "「" + labelOf(i) + "」無法從起點到達。", 5, ids.get(i));
                }
            }
            // 規則 6：從節點出發能到終點。出口有問題的節點視為終點（已另外報錯）
            Map<Integer, List<Integer>> reverse = new HashMap<>();
            Deque<Integer> queue = new ArrayDeque<>();
            Set<Integer> canEnd = new HashSet<>();
            for (int i = 0; i < nodes.size(); i++) {
                if (nodes.get(i) == null) {
                    continue;
                }
                for (int t : edges.getOrDefault(i, List.of())) {
                    reverse.computeIfAbsent(t, k -> new ArrayList<>()).add(i);
                }
                if (TERMINALS.contains((String) nodes.get(i).get("type")) || brokenOutputs.contains(i)) {
                    canEnd.add(i);
                    queue.add(i);
                }
            }
            while (!queue.isEmpty()) {
                int cur = queue.poll();
                for (int p : reverse.getOrDefault(cur, List.of())) {
                    if (canEnd.add(p)) {
                        queue.add(p);
                    }
                }
            }
            for (int i : reached) {
                if (!canEnd.contains(i)) {
                    err("definition.nodes[" + i + "]", "「" + labelOf(i) + "」之後走不到任何結束節點。", 6, ids.get(i));
                }
            }
        }

        private static Set<Integer> bfs(int from, Map<Integer, List<Integer>> graph) {
            Set<Integer> seen = new HashSet<>();
            Deque<Integer> queue = new ArrayDeque<>();
            seen.add(from);
            queue.add(from);
            while (!queue.isEmpty()) {
                int cur = queue.poll();
                for (int t : graph.getOrDefault(cur, List.of())) {
                    if (seen.add(t)) {
                        queue.add(t);
                    }
                }
            }
            return seen;
        }

        /** 規則 7：每個含迴圈的強連通分量須有等待節點；一個違規的迴圈一筆，掛在分量中陣列最前的節點。 */
        private void checkLoops() {
            int n = nodes.size();
            int[] index = new int[n];
            int[] low = new int[n];
            boolean[] onStack = new boolean[n];
            java.util.Arrays.fill(index, -1);
            int[] counter = {0};
            Deque<Integer> stack = new ArrayDeque<>();
            List<List<Integer>> components = new ArrayList<>();
            for (int v = 0; v < n; v++) {
                if (nodes.get(v) != null && index[v] < 0) {
                    strongConnect(v, index, low, onStack, counter, stack, components);
                }
            }
            components.sort((a, b) -> Integer.compare(min(a), min(b)));
            for (List<Integer> comp : components) {
                boolean loop = comp.size() > 1 || edges.getOrDefault(comp.get(0), List.of()).contains(comp.get(0));
                if (!loop) {
                    continue;
                }
                boolean hasWait = comp.stream().anyMatch(i -> WAITS.contains((String) nodes.get(i).get("type")));
                if (hasWait) {
                    continue;
                }
                List<Integer> ordered = new ArrayList<>(comp);
                java.util.Collections.sort(ordered);
                int first = ordered.get(0);
                StringBuilder list = new StringBuilder();
                for (int k = 0; k < ordered.size(); k++) {
                    list.append(k == 0 ? "" : "、").append(ids.get(ordered.get(k)));
                }
                err(
                        "definition.nodes[" + first + "]",
                        "迴圈 " + list + " 中沒有等待節點，會無限循環。",
                        7,
                        ids.get(first));
            }
        }

        private static int min(List<Integer> l) {
            return l.stream().mapToInt(Integer::intValue).min().orElse(0);
        }

        private void strongConnect(
                int root,
                int[] index,
                int[] low,
                boolean[] onStack,
                int[] counter,
                Deque<Integer> stack,
                List<List<Integer>> out) {
            // 迭代版 Tarjan：work 存 (節點, 下一個要看的邊)
            Deque<int[]> work = new ArrayDeque<>();
            work.push(new int[] {root, 0});
            index[root] = counter[0];
            low[root] = counter[0]++;
            stack.push(root);
            onStack[root] = true;
            while (!work.isEmpty()) {
                int[] frame = work.peek();
                int v = frame[0];
                List<Integer> out1 = edges.getOrDefault(v, List.of());
                if (frame[1] < out1.size()) {
                    int w = out1.get(frame[1]++);
                    if (index[w] < 0) {
                        index[w] = counter[0];
                        low[w] = counter[0]++;
                        stack.push(w);
                        onStack[w] = true;
                        work.push(new int[] {w, 0});
                    } else if (onStack[w]) {
                        low[v] = Math.min(low[v], index[w]);
                    }
                } else {
                    work.pop();
                    if (!work.isEmpty()) {
                        int parent = work.peek()[0];
                        low[parent] = Math.min(low[parent], low[v]);
                    }
                    if (low[v] == index[v]) {
                        List<Integer> comp = new ArrayList<>();
                        int w;
                        do {
                            w = stack.pop();
                            onStack[w] = false;
                            comp.add(w);
                        } while (w != v);
                        out.add(comp);
                    }
                }
            }
        }

        // ---------- settings ----------

        @SuppressWarnings("unchecked")
        private void checkSettings() {
            if (!(def.get("settings") instanceof Map<?, ?> raw)) {
                return;
            }
            Map<String, Object> settings = (Map<String, Object>) raw;
            Object reentry = settings.get("reentry");
            if (reentry != null) {
                if (!(reentry instanceof Map<?, ?> r)) {
                    err("definition.settings.reentry", "重入設定須為物件。", null, null);
                } else {
                    Object mode = r.get("mode");
                    if (!(mode instanceof String m) || !REENTRY_MODES.contains(m)) {
                        err("definition.settings.reentry.mode", "重入模式只支援 " + String.join("、", REENTRY_MODES.stream().sorted().toList()) + "。", null, null);
                    } else {
                        if (m.contains("DEDUP_KEY") && !(r.get("dedup_key_path") instanceof String p && !p.isBlank())) {
                            err("definition.settings.reentry.dedup_key_path", "此重入模式需要去重鍵（dedup_key_path）。", null, null);
                        }
                        if (m.contains("COOLDOWN")) {
                            checkDuration(r.get("cooldown"), "definition.settings.reentry.cooldown", null, true);
                        }
                    }
                }
            }
            Object goal = settings.get("goal");
            if (goal != null) {
                if (!(goal instanceof Map<?, ?> g)) {
                    err("definition.settings.goal", "目標設定須為物件。", null, null);
                } else {
                    if (!(g.get("condition") instanceof Map<?, ?> c)
                            || !"event".equals(c.get("type"))
                            || !(c.get("event") instanceof String ev && !ev.isBlank())) {
                        err("definition.settings.goal.condition", "目標條件本版只支援事件（type 為 event，並指定 event）。", null, null);
                    }
                    if (g.get("window") != null) {
                        checkDuration(g.get("window"), "definition.settings.goal.window", null, true);
                    }
                }
            }
            Object exit = settings.get("global_exit");
            if (exit != null) {
                checkGlobalExit(exit);
            }
        }

        private void checkGlobalExit(Object exit) {
            String base = "definition.settings.global_exit";
            if (!(exit instanceof Map<?, ?> g) || !(g.get("conditions") instanceof List<?> conds)) {
                err(base, "全域退出須為含 conditions 清單的物件。", null, null);
                return;
            }
            if (g.get("on_match") != null && !("EXITED".equals(g.get("on_match")) || "GOAL_MET".equals(g.get("on_match")))) {
                err(base + ".on_match", "on_match 只支援 EXITED、GOAL_MET。", null, null);
            }
            for (int i = 0; i < conds.size(); i++) {
                String f = base + ".conditions[" + i + "]";
                if (!(conds.get(i) instanceof Map<?, ?> c)) {
                    err(f, "條件須為物件。", null, null);
                } else if ("event".equals(c.get("type"))) {
                    if (!(c.get("event") instanceof String ev && !ev.isBlank())) {
                        err(f + ".event", "事件名稱必填。", null, null);
                    }
                    Object since = c.get("since");
                    if (since != null && !"ENROLLMENT".equals(since)) {
                        err(f + ".since", "since 本版只支援 ENROLLMENT。", null, null);
                    }
                } else if ("attribute".equals(c.get("type"))) {
                    checkCondition(c, f, null, MAX_DEPTH, false);
                } else {
                    err(f + ".type", "全域退出條件只支援 event、attribute。", null, null);
                }
            }
        }

        // ---------- 警告 ----------

        @SuppressWarnings("unchecked")
        private void checkWarnings() {
            Map<String, Object> settings = def.get("settings") instanceof Map<?, ?> s ? (Map<String, Object>) s : Map.of();
            boolean globalExit = settings.get("global_exit") != null;
            boolean goalNode = false;
            for (int i = 0; i < nodes.size(); i++) {
                Map<String, Object> n = nodes.get(i);
                if (n == null) {
                    continue;
                }
                if ("GoalNode".equals(n.get("type"))) {
                    goalNode = true;
                }
                if ("WaitForEventNode".equals(n.get("type"))
                        && !globalExit
                        && n.get("config") instanceof Map<?, ?> c
                        && c.get("timeout") == null) {
                    warnings.add(
                            new Issue(
                                    "definition.nodes[" + i + "]",
                                    "「" + labelOf(i) + "」不會逾時，旅程也沒有設定全域退出，客戶可能一直等下去。",
                                    12,
                                    ids.get(i)));
                }
            }
            if (!goalNode && settings.get("goal") == null) {
                warnings.add(new Issue("definition.settings.goal", "旅程沒有設定目標，也沒有目標節點。", 13, null));
            }
        }

        private void err(String field, String message, Integer rule, String nodeId) {
            errors.add(new Issue(field, message, rule, nodeId));
        }
    }
}
