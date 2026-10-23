package com.cdp.user;

import java.time.Instant;

/** auth.md §4 的 User（不含密碼與雜湊）。 */
public record UserResponse(
        String userId,
        String email,
        String displayName,
        UserRole role,
        UserStatus status,
        Instant createdAt,
        Instant updatedAt) {

    static UserResponse of(AppUser u) {
        return new UserResponse(
                u.userId(), u.email(), u.displayName(), u.role(), u.status(), u.createdAt(), u.updatedAt());
    }
}
