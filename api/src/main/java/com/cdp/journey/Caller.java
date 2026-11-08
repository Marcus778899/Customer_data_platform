package com.cdp.journey;

import org.springframework.security.core.Authentication;

import com.cdp.security.Role;
import com.cdp.security.SessionUser;
import com.cdp.user.UserRole;

/** 呼叫者：session 使用者（有 userId）或金鑰（userId 為 null，紀錄上顯示為 null）。 */
public record Caller(String userId, boolean admin) {

    public static Caller of(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof SessionUser u) {
            return new Caller(u.userId(), u.role() == UserRole.ADMIN);
        }
        boolean admin =
                auth != null && auth.getAuthorities().stream().anyMatch(a -> Role.ADMIN.authority().equals(a.getAuthority()));
        return new Caller(null, admin);
    }
}
