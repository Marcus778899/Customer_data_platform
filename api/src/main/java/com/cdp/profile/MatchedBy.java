package com.cdp.profile;

/** profiles.md §1 {@code ProfileSummary.matched_by}；宣告順序即「最先命中」的優先序。 */
public enum MatchedBy {
    PROFILE_ID,
    EMAIL,
    PHONE,
    MEMBER_ID,
    LINE_USER_ID,
    DEVICE_ID;

    public static MatchedBy of(IdentifierType type) {
        return valueOf(type.name());
    }
}
