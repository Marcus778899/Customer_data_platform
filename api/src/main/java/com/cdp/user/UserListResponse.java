package com.cdp.user;

import java.util.List;

/** GET /api/v1/users。 */
public record UserListResponse(List<UserResponse> items) {}
