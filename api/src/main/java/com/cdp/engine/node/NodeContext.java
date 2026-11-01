package com.cdp.engine.node;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.cdp.engine.model.DeliveryRecord;
import com.cdp.engine.model.TriggerSnapshot;
import com.cdp.engine.model.WakeSignal;

/** 節點輸入，純資料（04 §3）。now 來自注入的 Clock。 */
public record NodeContext(
        String enrollmentId,
        String profileId,
        String nodeId,
        NodeConfig config,
        Map<String, Object> profileFields,
        Map<String, Boolean> segmentMemberships,
        Map<String, Object> behaviorResults,
        List<DeliveryRecord> deliveryHistory,
        TriggerSnapshot trigger,
        Map<String, Object> variables,
        Instant enrolledAt,
        WakeSignal wakeSignal,
        Instant now) {}
