package com.cdp.engine.node.builtin;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.cdp.engine.node.DataRequirements;
import com.cdp.engine.node.JourneyNode;
import com.cdp.engine.node.NodeConfig;
import com.cdp.engine.node.NodeContext;
import com.cdp.engine.node.NodeResult;
import com.cdp.engine.node.ValidationResult;
import com.cdp.journey.JourneyDefinition;

/**
 * A/B 分流（spec 03 SplitTestNode）。STABLE：variables 已有分組就沿用；否則 SHA-256(journey_id:node_id:profile_id) 前 8
 * 位元組（無號）mod 100，依 variants 順序的累積權重取第一個 bucket 小於累積值者；結果寫入 variables.__split_nodeId。
 */
@Component
public final class SplitTestNode implements JourneyNode {

    @Override
    public String type() {
        return "SplitTestNode";
    }

    @Override
    public DataRequirements requirements(NodeConfig config) {
        return DataRequirements.NONE;
    }

    @Override
    public NodeResult execute(NodeContext context) {
        String key = "__split_" + context.nodeId();
        Map<String, Object> evaluated = new LinkedHashMap<>();
        Object existing = context.variables() == null ? null : context.variables().get(key);
        if (existing instanceof String port) {
            evaluated.put("variant", port);
            return NodeResult.advanceWithVariables(
                    port,
                    Map.of(key, port),
                    "節點 " + context.nodeId() + " 沿用先前的分組：" + port + "。",
                    evaluated);
        }
        List<?> variants = context.config().get("variants", List.class);
        if (variants == null || variants.isEmpty()) {
            throw new IllegalStateException("SplitTestNode 沒有 variants");
        }
        long bucket = bucket(context);
        long cumulative = 0;
        String chosen = null;
        for (Object v : variants) {
            Map<?, ?> variant = (Map<?, ?>) v;
            cumulative += ((Number) variant.get("weight")).longValue();
            if (bucket < cumulative) {
                chosen = (String) variant.get("port");
                break;
            }
        }
        if (chosen == null) {
            chosen = (String) ((Map<?, ?>) variants.get(variants.size() - 1)).get("port");
        }
        evaluated.put("bucket", bucket);
        evaluated.put("variant", chosen);
        return NodeResult.advanceWithVariables(
                chosen,
                Map.of(key, chosen),
                "節點 " + context.nodeId() + " 將客戶分到 " + chosen + " 組。",
                evaluated);
    }

    private static long bucket(NodeContext context) {
        try {
            byte[] hash =
                    MessageDigest.getInstance("SHA-256")
                            .digest(
                                    (context.journeyId() + ":" + context.nodeId() + ":" + context.profileId())
                                            .getBytes(StandardCharsets.UTF_8));
            return new BigInteger(1, Arrays.copyOf(hash, 8)).mod(BigInteger.valueOf(100)).longValue();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public ValidationResult validate(NodeConfig config, JourneyDefinition journey) {
        List<?> variants = config.get("variants", List.class);
        if (variants == null || variants.size() < 2) {
            return new ValidationResult(List.of("SplitTestNode.config.variants 至少 2 組"));
        }
        return ValidationResult.OK;
    }
}
