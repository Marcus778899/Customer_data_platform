package com.cdp.user;

import java.nio.charset.StandardCharsets;

/** 密碼規則（auth.md §3）：12–72 字元且 UTF-8 位元組數 ≤ 72（BCrypt 上限）；不檢查複雜度。 */
public final class PasswordPolicy {

    public static final int MIN_CHARS = 12;
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {}

    public static boolean valid(String password) {
        return password != null
                && password.codePointCount(0, password.length()) >= MIN_CHARS
                && password.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }

    public static final String MESSAGE = "密碼須為 12–72 字元（UTF-8 不超過 72 位元組）";
}
