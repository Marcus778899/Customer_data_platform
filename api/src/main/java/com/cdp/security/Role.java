package com.cdp.security;

/**
 * 呼叫方角色（auth.md §1、§2）。INGEST 與使用者角色無階層關係；使用者角色 ADMIN ⊇ EDITOR ⊇ VIEWER
 * 由 {@link SecurityConfig#roleHierarchy()} 表達。管理金鑰 = ADMIN。
 */
public enum Role {
    /** 外部系統：只能呼叫事件攝取等 INGEST 列出的 API。憑證 = cdp.security.ingest-api-key。 */
    INGEST,
    VIEWER,
    EDITOR,
    /** 憑證 = 管理金鑰（cdp.security.admin-api-key）或 role = ADMIN 的使用者 session。 */
    ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }
}
