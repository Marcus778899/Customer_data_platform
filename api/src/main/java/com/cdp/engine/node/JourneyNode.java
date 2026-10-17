package com.cdp.engine.node;

import com.cdp.journey.JourneyDefinition;

/** 節點介面（04 §2）。實作必須無欄位、不依賴 Repository（ArchitectureTest）。 */
public interface JourneyNode {

    /** 與旅程定義 JSON 的 type 一致，例：WaitDelayNode。 */
    String type();

    DataRequirements requirements(NodeConfig config);

    /** 純函式；時間只用 context.now()。 */
    NodeResult execute(NodeContext context);

    ValidationResult validate(NodeConfig config, JourneyDefinition journey);
}
