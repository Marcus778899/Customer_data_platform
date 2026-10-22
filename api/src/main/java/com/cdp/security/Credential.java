package com.cdp.security;

/** 憑證種類，以額外的 authority 附在 Authentication 上，讓角色矩陣能區分「僅管理金鑰」「僅 session」。 */
public enum Credential {
    /** Authorization: Bearer（攝取金鑰或管理金鑰）。 */
    BEARER,
    /** cookie CDP_SESSION 對應的使用者。 */
    SESSION;

    public String authority() {
        return "CRED_" + name();
    }
}
