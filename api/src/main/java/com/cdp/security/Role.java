package com.cdp.security;

/** 呼叫方角色與可用 API（契約 §1.1）。 */
public enum Role {
    /** 外部系統：只能呼叫事件攝取 §3.2。憑證 = cdp.security.ingest-api-key。 */
    INGEST,
    /** 管理（建旅程、腳本、測試）：§3.1 與 §4。憑證 = cdp.security.admin-api-key。 */
    ADMIN,
    /** 客服：登入 session，只能 GET §4 與 §1.2。 */
    SUPPORT
}
