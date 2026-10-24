package com.cdp.profile;

/** profiles.md §1 {@code IdentifierType}（同 spec 01 §3）。宣告順序即搜尋規則 4 的比對順序。 */
public enum IdentifierType {
    EMAIL,
    PHONE,
    MEMBER_ID,
    LINE_USER_ID,
    DEVICE_ID
}
