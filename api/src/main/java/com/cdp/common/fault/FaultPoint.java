package com.cdp.common.fault;

/** 驗收「中斷重啟恰好一則」用的故障注入點（DESIGN-01 做法 #5）。 */
public enum FaultPoint {
    /** relay 取到 PENDING 列、推送之前：模擬「狀態已提交、未推送」。 */
    RELAY_BEFORE_PUBLISH,
    /** broker 已 confirm、標 PUBLISHED 之前：模擬重複推送。 */
    RELAY_AFTER_CONFIRM,
    /** 整批 confirm 等完、條件式回寫之前：測試用來製造「遲到的 confirm」（lease 已被另一輪接手）。 */
    RELAY_BEFORE_WRITEBACK,
    /** consumer 交易已提交、ack 之前：模擬「已推送、consumer 未 ack」。 */
    CONSUMER_AFTER_COMMIT
}
