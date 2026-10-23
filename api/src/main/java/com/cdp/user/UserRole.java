package com.cdp.user;

/** 使用者角色（auth.md §2）；階層 ADMIN ⊇ EDITOR ⊇ VIEWER 由 security 的 RoleHierarchy 表達。 */
public enum UserRole {
    VIEWER,
    EDITOR,
    ADMIN
}
