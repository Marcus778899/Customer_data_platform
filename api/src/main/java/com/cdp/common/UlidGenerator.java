package com.cdp.common;

import java.security.SecureRandom;
import java.time.Clock;

import org.springframework.stereotype.Component;

/** ULID：時間取自注入的 Clock，隨機段用 SecureRandom；不引入第三方套件。 */
@Component
public class UlidGenerator implements IdGenerator {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public UlidGenerator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String newId() {
        long millis = clock.millis();
        char[] out = new char[26];
        // 時間段：48 bit，10 個字元（高位在前）
        for (int i = 9; i >= 0; i--) {
            out[i] = ALPHABET[(int) (millis & 31)];
            millis >>>= 5;
        }
        // 隨機段：80 bit，16 個字元
        for (int i = 10; i < 26; i++) {
            out[i] = ALPHABET[random.nextInt(32)];
        }
        return new String(out);
    }
}
