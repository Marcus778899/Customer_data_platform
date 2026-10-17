package com.cdp.journey;

import java.time.Clock;

import org.springframework.stereotype.Service;

import com.cdp.common.IdGenerator;
import com.cdp.engine.runtime.NodeRegistry;

/** 建立旅程版本：圖驗證（v0.1.0 子集，契約 §3.1）+ 各節點 validate，通過即 ACTIVE。 */
@Service
public class JourneyService {

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

    public JourneyCreatedResponse create(String tenantId, CreateJourneyRequest request) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }
}
