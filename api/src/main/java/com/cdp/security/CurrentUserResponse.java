package com.cdp.security;

/** 契約 §1.2 GET /api/v1/auth/me。 */
public record CurrentUserResponse(String username) {}
