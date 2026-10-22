package com.cdp.user;

/** PATCH /api/v1/users/{id} 的 body；欄位為 null 代表不修改。 */
public record UpdateUserRequest(String displayName, String role, String status, String password) {

    @Override
    public String toString() {
        return "UpdateUserRequest[role=" + role + ", status=" + status + ", password=***]";
    }
}
