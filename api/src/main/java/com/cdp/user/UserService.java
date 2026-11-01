package com.cdp.user;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.cdp.common.IdGenerator;
import com.cdp.common.web.ApiException;
import com.cdp.common.web.ErrorCode;
import com.cdp.common.web.FieldError;

/**
 * 使用者建立、查詢與修改（auth.md §4）。LAST_ADMIN 檢查與更新在同一個交易：先鎖定所有啟用中的 ADMIN（固定順序）
 * 再鎖目標列，並行的降級／停用因此逐一序列化，不會把啟用中的 ADMIN 變成 0 位。BCrypt 在交易外先算好，不佔著鎖。
 */
@Service
public class UserService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int EMAIL_MAX = 254;
    private static final int NAME_MAX = 100;

    private final AppUserStore store;
    private final PasswordHasher hasher;
    private final IdGenerator ids;
    private final Clock clock;
    private final TransactionTemplate tx;

    public UserService(
            AppUserStore store, PasswordHasher hasher, IdGenerator ids, Clock clock, TransactionTemplate tx) {
        this.store = store;
        this.hasher = hasher;
        this.ids = ids;
        this.clock = clock;
        this.tx = tx;
    }

    /** 登入用：email 不分大小寫。 */
    public Optional<AppUser> findByEmail(String email) {
        return store.findByEmail(normalizeEmail(email));
    }

    /** 每個請求重新載入 session 的使用者；停用者視為不存在。 */
    public Optional<AppUser> findActiveById(String userId) {
        return store.findById(userId).filter(AppUser::active);
    }

    public List<UserResponse> list() {
        return store.findAllByEmail().stream().map(UserResponse::of).toList();
    }

    public UserResponse create(CreateUserRequest req) {
        List<FieldError> errors = new ArrayList<>();
        String email = req.email() == null ? null : normalizeEmail(req.email());
        if (email == null || email.length() > EMAIL_MAX || !EMAIL.matcher(email).matches()) {
            errors.add(new FieldError("email", "須為有效的 email"));
        }
        validateName(req.displayName(), true, errors);
        UserRole role = parseRole(req.role(), true, errors);
        if (!PasswordPolicy.valid(req.password())) {
            errors.add(new FieldError("password", PasswordPolicy.MESSAGE));
        }
        failIfAny(errors);

        Instant now = now();
        AppUser user = new AppUser(
                ids.newId(),
                email,
                req.displayName(),
                hasher.hash(req.password()),
                role,
                UserStatus.ACTIVE,
                now,
                now,
                0);
        try {
            store.insert(user);
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.EMAIL_TAKEN, "這個 email 已被使用。");
        }
        return UserResponse.of(user);
    }

    public UserResponse update(String userId, UpdateUserRequest req) {
        List<FieldError> errors = new ArrayList<>();
        validateName(req.displayName(), false, errors);
        UserRole newRole = parseRole(req.role(), false, errors);
        UserStatus newStatus = parseStatus(req.status(), errors);
        if (req.password() != null && !PasswordPolicy.valid(req.password())) {
            errors.add(new FieldError("password", PasswordPolicy.MESSAGE));
        }
        failIfAny(errors);
        String newHash = req.password() == null ? null : hasher.hash(req.password());

        AppUser updated = tx.execute(status -> {
            List<String> activeAdmins = store.lockActiveAdminIds();
            AppUser current = store.lockById(userId)
                    .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND, "找不到這位使用者。"));
            UserRole role = newRole != null ? newRole : current.role();
            UserStatus st = newStatus != null ? newStatus : current.status();
            boolean isActiveAdmin = activeAdmins.contains(userId);
            boolean staysActiveAdmin = role == UserRole.ADMIN && st == UserStatus.ACTIVE;
            if (isActiveAdmin && !staysActiveAdmin && activeAdmins.size() <= 1) {
                throw new ApiException(ErrorCode.LAST_ADMIN, "至少要保留一位啟用中的管理員。");
            }
            store.update(
                    userId,
                    req.displayName() != null ? req.displayName() : current.displayName(),
                    role,
                    st,
                    newHash != null ? newHash : current.passwordHash(),
                    newHash != null,
                    now());
            return store.findById(userId).orElseThrow();
        });
        return UserResponse.of(updated);
    }

    /** 使用者自己改密碼（auth.md §3）：舊密碼錯 → 401 INVALID_CREDENTIALS；其他 session 不登出。 */
    public void changePassword(String userId, String currentPassword, String newPassword) {
        List<FieldError> errors = new ArrayList<>();
        if (currentPassword == null || currentPassword.isEmpty()) {
            errors.add(new FieldError("current_password", "必填"));
        }
        if (!PasswordPolicy.valid(newPassword)) {
            errors.add(new FieldError("new_password", PasswordPolicy.MESSAGE));
        }
        failIfAny(errors);
        AppUser user = store.findById(userId)
                .filter(AppUser::active)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "缺少有效的憑證"));
        if (!hasher.matches(currentPassword, user.passwordHash())) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "目前的密碼錯誤");
        }
        String newHash = hasher.hash(newPassword);
        tx.executeWithoutResult(status -> store.lockById(userId)
                .ifPresent(u -> store.update(userId, u.displayName(), u.role(), u.status(), newHash, false, now())));
    }

    private Instant now() {
        // PostgreSQL 只存到微秒；回應與之後讀回的值必須一致
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static void validateName(String name, boolean required, List<FieldError> errors) {
        if (name == null) {
            if (required) {
                errors.add(new FieldError("display_name", "必填"));
            }
            return;
        }
        if (name.isBlank() || name.codePointCount(0, name.length()) > NAME_MAX) {
            errors.add(new FieldError("display_name", "須為 1–100 字"));
        }
    }

    private static UserRole parseRole(String value, boolean required, List<FieldError> errors) {
        if (value == null) {
            if (required) {
                errors.add(new FieldError("role", "必填"));
            }
            return null;
        }
        try {
            return UserRole.valueOf(value);
        } catch (IllegalArgumentException e) {
            errors.add(new FieldError("role", "須為 VIEWER、EDITOR 或 ADMIN"));
            return null;
        }
    }

    private static UserStatus parseStatus(String value, List<FieldError> errors) {
        if (value == null) {
            return null;
        }
        try {
            return UserStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            errors.add(new FieldError("status", "須為 ACTIVE 或 DISABLED"));
            return null;
        }
    }

    private static void failIfAny(List<FieldError> errors) {
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "請求內容有誤", errors);
        }
    }
}
