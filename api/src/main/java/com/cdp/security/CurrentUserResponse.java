package com.cdp.security;

import com.cdp.user.UserRole;

/** auth.md §3 GET /api/v1/auth/me 的 CurrentUser。 */
public record CurrentUserResponse(String userId, String email, String displayName, UserRole role) {}
