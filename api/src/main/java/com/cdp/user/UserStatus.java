package com.cdp.user;

/** 使用者狀態（auth.md §4）；停用即不能登入、既有 session 下一個請求失效。 */
public enum UserStatus {
    ACTIVE,
    DISABLED
}
