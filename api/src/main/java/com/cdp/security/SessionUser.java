package com.cdp.security;

import com.cdp.user.AppUser;
import com.cdp.user.UserRole;

/** session 認證的 principal：每個請求由資料庫重新載入，所以角色與停用即時生效。 */
public record SessionUser(String userId, String email, String displayName, UserRole role) {

    static SessionUser of(AppUser u) {
        return new SessionUser(u.userId(), u.email(), u.displayName(), u.role());
    }
}
