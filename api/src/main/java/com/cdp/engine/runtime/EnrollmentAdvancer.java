package com.cdp.engine.runtime;

import com.cdp.engine.model.EnrollmentState;
import com.cdp.engine.model.WakeSignal;

/**
 * 推進迴圈步驟 2~9（04 §6）：載入綁定版本 → execute → 單一交易 save → 連續推進（上限 50 個節點）。 v0.1.0 不評估
 * settings.goal／global_exit（v0.2.0）。
 */
public interface EnrollmentAdvancer {

    /** wakeSignal 為 null 表示新建立後的首次推進。 */
    EnrollmentState advance(EnrollmentState state, WakeSignal wakeSignal);
}
