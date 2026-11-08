package com.cdp.journey;

import org.springframework.stereotype.Component;

import com.cdp.user.AppUserStore;

/** 由使用者 ID 帶出目前的顯示名稱（停用者照常帶出）。 */
@Component
public class UserRefs {

    private final AppUserStore users;

    public UserRefs(AppUserStore users) {
        this.users = users;
    }

    /** userId 為 null（金鑰呼叫）回 null；使用者查不到時以 ID 當名稱。 */
    public UserRef of(String userId) {
        if (userId == null) {
            return null;
        }
        return users.findById(userId)
                .map(u -> new UserRef(u.userId(), u.displayName()))
                .orElse(new UserRef(userId, userId));
    }
}
