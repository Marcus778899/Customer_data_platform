package com.cdp.journey;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.cdp.common.IdGenerator;
import com.cdp.common.web.ApiException;
import com.cdp.common.web.ErrorCode;
import com.cdp.common.web.FieldError;
import com.cdp.engine.node.NodeConfig;
import com.cdp.engine.runtime.NodeRegistry;

/** 建立旅程版本：圖驗證（v0.1.0 子集，契約 §3.1）+ 各節點 validate，通過即 ACTIVE。 */
@Service
public class JourneyService {

    private static final Pattern ULID = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");

    /** 各節點型別必備的出口（契約 §3.1）；skipped 可省略。 */
    private static final Map<String, String> REQUIRED_PORT =
            Map.of(
                    "StartNode", "next",
                    "EventSourceNode", "next",
                    "WaitDelayNode", "next",
                    "SendMessageNode", "sent");

    private final JourneyDefinitionStore store;
    private final NodeRegistry nodes;
    private final IdGenerator ids;
    private final Clock clock;

    public JourneyService(
            JourneyDefinitionStore store, NodeRegistry nodes, IdGenerator ids, Clock clock) {
        this.store = store;
        this.nodes = nodes;
        this.ids = ids;
        this.clock = clock;
    }

    public JourneyCreatedResponse create(CreateJourneyRequest request) {
        List<FieldError> errors = new ArrayList<>();
        if (request == null) {
            errors.add(new FieldError("body", "請求內容不可為空"));
            throw invalid(errors);
        }
        validate(request, errors);
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }

        String journeyId = request.journeyId();
        if (journeyId == null) {
            journeyId = ids.newId();
        } else if (store.find(journeyId, 1).isEmpty()) {
            throw new ApiException(ErrorCode.JOURNEY_NOT_FOUND, "找不到這個旅程，無法建立新版本。");
        }

        Instant now = clock.instant();
        JourneyDefinition saved =
                store.insertNewVersion(
                        new JourneyDefinition(
                                journeyId,
                                0,
                                request.name(),
                                request.description(),
                                JourneyStatus.ACTIVE,
                                request.settings() == null ? Map.of() : request.settings(),
                                request.entryNode(),
                                request.nodes(),
                                now,
                                now));
        return new JourneyCreatedResponse(
                saved.journeyId(), saved.version(), saved.status(), saved.publishedAt());
    }

    private static ApiException invalid(List<FieldError> errors) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "旅程定義驗證失敗。", errors);
    }

    private void validate(CreateJourneyRequest request, List<FieldError> errors) {
        if (request.name() == null || request.name().isBlank()) {
            errors.add(new FieldError("name", "必填"));
        }
        if (request.journeyId() != null && !ULID.matcher(request.journeyId()).matches()) {
            errors.add(new FieldError("journey_id", "須為 ULID"));
        }
        List<JourneyNodeDefinition> list = request.nodes();
        if (list == null || list.isEmpty()) {
            errors.add(new FieldError("nodes", "必填，至少一個節點"));
            return;
        }

        // 節點基本欄位：id 唯一、型別支援
        Map<String, JourneyNodeDefinition> byId = new HashMap<>();
        for (int i = 0; i < list.size(); i++) {
            JourneyNodeDefinition n = list.get(i);
            String base = "nodes[" + i + "]";
            if (n == null || n.id() == null || n.id().isBlank()) {
                errors.add(new FieldError(base + ".id", "必填"));
                continue;
            }
            if (byId.put(n.id(), n) != null) {
                errors.add(new FieldError(base + ".id", "節點 id 重複：" + n.id()));
            }
            if (!nodes.supports(n.type())) {
                errors.add(
                        new FieldError(
                                base + ".type",
                                "不支援的節點型別；v0.1.0 只接受 StartNode、EventSourceNode、"
                                        + "WaitDelayNode、SendMessageNode、EndNode"));
            }
        }
        if (!errors.isEmpty()) {
            return;
        }

        // entry_node 指向唯一的 StartNode
        List<JourneyNodeDefinition> starts =
                list.stream().filter(n -> "StartNode".equals(n.type())).toList();
        if (starts.size() != 1) {
            errors.add(new FieldError("nodes", "須有且只有一個 StartNode"));
        } else if (!starts.get(0).id().equals(request.entryNode())) {
            errors.add(new FieldError("entry_node", "須指向 StartNode：" + starts.get(0).id()));
        }

        // 各節點設定與出口
        JourneyDefinition draft =
                new JourneyDefinition(
                        request.journeyId(),
                        0,
                        request.name(),
                        request.description(),
                        JourneyStatus.ACTIVE,
                        request.settings(),
                        request.entryNode(),
                        list,
                        null,
                        null);
        Map<String, List<String>> edges = new HashMap<>();
        for (int i = 0; i < list.size(); i++) {
            JourneyNodeDefinition n = list.get(i);
            String base = "nodes[" + i + "]";
            Map<String, Object> config = n.config() == null ? Map.of() : n.config();
            for (String message :
                    nodes.get(n.type()).validate(new NodeConfig(config), draft).errors()) {
                errors.add(new FieldError(base + ".config", message));
            }
            validateOutputs(n, base, byId, edges, errors);
        }
        if (!errors.isEmpty()) {
            return;
        }

        // Start 的 next 須指向 EventSourceNode
        if (starts.size() == 1) {
            for (String target : edges.getOrDefault(starts.get(0).id(), List.of())) {
                if (!"EventSourceNode".equals(byId.get(target).type())) {
                    errors.add(new FieldError("nodes", "StartNode.outputs.next 須指向 EventSourceNode：" + target));
                }
            }
        }
        if (hasCycle(edges)) {
            errors.add(new FieldError("nodes", "旅程圖不可有迴圈（v0.1.0）"));
        }
    }

    private static void validateOutputs(
            JourneyNodeDefinition n,
            String base,
            Map<String, JourneyNodeDefinition> byId,
            Map<String, List<String>> edges,
            List<FieldError> errors) {
        Map<String, Object> outputs = n.outputs() == null ? Map.of() : n.outputs();
        String required = REQUIRED_PORT.get(n.type());
        if (required != null && !outputs.containsKey(required)) {
            errors.add(new FieldError(base + ".outputs", n.type() + " 需要出口 " + required));
        }
        List<String> targets = new ArrayList<>();
        for (Map.Entry<String, Object> e : outputs.entrySet()) {
            String field = base + ".outputs." + e.getKey();
            Object value = e.getValue();
            if (value instanceof String s) {
                targets.add(s);
            } else if (value instanceof List<?> l && "StartNode".equals(n.type())) {
                for (Object o : l) {
                    if (o instanceof String s) {
                        targets.add(s);
                    } else {
                        errors.add(new FieldError(field, "目標須為節點 id 字串"));
                    }
                }
            } else {
                errors.add(new FieldError(field, "目標須為節點 id 字串"));
            }
        }
        for (String target : targets) {
            if (!byId.containsKey(target)) {
                errors.add(new FieldError(base + ".outputs", "目標節點不存在：" + target));
            }
        }
        edges.put(n.id(), targets);
    }

    /** 迭代式 DFS 三色標記。 */
    private static boolean hasCycle(Map<String, List<String>> edges) {
        Set<String> done = new HashSet<>();
        Set<String> onPath = new HashSet<>();
        for (String start : edges.keySet()) {
            if (visit(start, edges, done, onPath)) {
                return true;
            }
        }
        return false;
    }

    private static boolean visit(
            String node, Map<String, List<String>> edges, Set<String> done, Set<String> onPath) {
        if (done.contains(node)) {
            return false;
        }
        if (!onPath.add(node)) {
            return true;
        }
        for (String next : edges.getOrDefault(node, List.of())) {
            if (visit(next, edges, done, onPath)) {
                return true;
            }
        }
        onPath.remove(node);
        done.add(node);
        return false;
    }
}
