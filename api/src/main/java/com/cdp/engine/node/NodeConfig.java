package com.cdp.engine.node;

import java.util.Map;

/** 節點 config（03 §5），值為 JSON 解析後的 Map／List／String／Number／Boolean。 */
public record NodeConfig(Map<String, Object> values) {

    /** 取設定值並轉型；缺值回 null。 */
    public <T> T get(String key, Class<T> type) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }
}
