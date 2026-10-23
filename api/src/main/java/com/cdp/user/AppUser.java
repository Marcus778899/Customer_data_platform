package com.cdp.user;

import java.time.Instant;

/** app_user 一列。passwordHash 不得輸出到日誌或回應，toString 不含它。 */
public record AppUser(
        String userId,
        String email,
        String displayName,
        String passwordHash,
        UserRole role,
        UserStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public boolean active() {
        return status == UserStatus.ACTIVE;
    }

    @Override
    public String toString() {
        return "AppUser[userId=" + userId + ", role=" + role + ", status=" + status + "]";
    }
}
