package com.cdp.security;

/** auth.md §3 POST /api/v1/auth/password 的 body。toString 不輸出密碼。 */
public record PasswordChangeRequest(String currentPassword, String newPassword) {

    @Override
    public String toString() {
        return "PasswordChangeRequest[currentPassword=***, newPassword=***]";
    }
}
