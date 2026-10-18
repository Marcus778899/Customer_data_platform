package com.cdp.security;

/** 契約 §1.2 POST /api/v1/auth/login 的 body。toString 不輸出帳密。 */
public record LoginRequest(String username, String password) {

    @Override
    public String toString() {
        return "LoginRequest[username=***, password=***]";
    }
}
