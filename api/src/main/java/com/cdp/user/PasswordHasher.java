package com.cdp.user;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** BCrypt 雜湊（spring-security-crypto）。帳號不存在時用 {@link #matchesDummy} 跑一次等價成本的比對，避免從耗時猜出帳號。 */
@Component
public class PasswordHasher {

    private final PasswordEncoder encoder;
    private final String dummyHash;

    public PasswordHasher() {
        this(new BCryptPasswordEncoder());
    }

    /** 測試可注入計數用的 encoder。 */
    PasswordHasher(PasswordEncoder encoder) {
        this.encoder = encoder;
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    public String hash(String password) {
        return encoder.encode(password);
    }

    /**
     * 超過 72 位元組或 hash 為 null 一律視為不符（BCrypt 只看前 72 位元組）；這兩種情況也對 dummy hash 跑一次
     * 比對，讓回應時間和一般路徑一致，不因密碼長度洩漏帳號是否存在。
     */
    public boolean matches(String password, String hash) {
        if (password == null || hash == null || password.getBytes(StandardCharsets.UTF_8).length > PasswordPolicy.MAX_BYTES) {
            encoder.matches("x", dummyHash);
            return false;
        }
        return encoder.matches(password, hash);
    }

    /** 跑一次比對成本、結果恆為 false。 */
    public boolean matchesDummy(String password) {
        boolean tooLong = password != null && password.getBytes(StandardCharsets.UTF_8).length > PasswordPolicy.MAX_BYTES;
        encoder.matches(password == null || tooLong ? "x" : password, dummyHash);
        return false;
    }
}
