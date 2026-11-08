package com.cdp.journey;

/** journeys.md §1 {@code UserRef}：後端帶出使用者目前的顯示名稱。 */
public record UserRef(String userId, String displayName) {}
