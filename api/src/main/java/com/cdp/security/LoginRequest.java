package com.cdp.security;

/** auth.md §3 POST /api/v1/auth/login 的 body。toString 不輸出帳密。 */
public record LoginRequest(String email, String password) {

    @Override
    public String toString() {
        return "LoginRequest[email=***, password=***]";
    }
}
