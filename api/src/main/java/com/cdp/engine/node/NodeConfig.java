package com.cdp.engine.node;

import java.util.Map;

/** 節點 config（03 §5），值為 JSON 解析後的 Map／List／String／Number／Boolean。 */
public record NodeConfig(Map<String, Object> values) {

    /** 取設定值並轉型；缺值或型別不符回 null（發布前由 validate 擋下型別不符）。 */
    public <T> T get(String key, Class<T> type) {
        if (values == null) {
            return null;
        }
        Object value = values.get(key);
        return type.isInstance(value) ? type.cast(value) : null;
    }
}
