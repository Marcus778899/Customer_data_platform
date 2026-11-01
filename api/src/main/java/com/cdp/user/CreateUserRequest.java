package com.cdp.user;

/** POST /api/v1/users 的 body；欄位型別全用字串，由 {@link UserService} 驗證並回 400。 */
public record CreateUserRequest(String email, String displayName, String role, String password) {

    @Override
    public String toString() {
        return "CreateUserRequest[email=" + email + ", role=" + role + ", password=***]";
    }
}
