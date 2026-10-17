package com.cdp.security;

/** 契約 §1.2 POST /api/v1/auth/login 的 body。 */
public record LoginRequest(String username, String password) {}
