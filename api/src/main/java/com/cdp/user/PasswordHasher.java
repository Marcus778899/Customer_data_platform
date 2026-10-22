package com.cdp.user;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/** BCrypt 雜湊（spring-security-crypto）。帳號不存在時用 {@link #matchesDummy} 跑一次等價成本的比對，避免從耗時猜出帳號。 */
@Component
public class PasswordHasher {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final String dummyHash = encoder.encode(UUID.randomUUID().toString());

    public String hash(String password) {
        return encoder.encode(password);
    }

    /** 超過 72 位元組一律視為不符（BCrypt 只看前 72 位元組，不讓截斷造成誤判）。 */
    public boolean matches(String password, String hash) {
        if (password == null || hash == null || password.getBytes(StandardCharsets.UTF_8).length > PasswordPolicy.MAX_BYTES) {
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
