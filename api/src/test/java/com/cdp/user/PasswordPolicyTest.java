package com.cdp.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    @Test
    void length_is_12_to_72_chars_and_at_most_72_utf8_bytes() {
        assertThat(PasswordPolicy.valid(null)).isFalse();
        assertThat(PasswordPolicy.valid("a".repeat(11))).isFalse();
        assertThat(PasswordPolicy.valid("a".repeat(12))).isTrue();
        assertThat(PasswordPolicy.valid("a".repeat(72))).isTrue();
        assertThat(PasswordPolicy.valid("a".repeat(73))).isFalse();
        assertThat(PasswordPolicy.valid("密".repeat(24))).as("72 bytes").isTrue();
        assertThat(PasswordPolicy.valid("密".repeat(25))).as("75 bytes").isFalse();
        assertThat(PasswordPolicy.valid("密".repeat(11))).isFalse();
    }

    @Test
    void hasher_verifies_and_rejects_over_72_bytes_without_throwing() {
        PasswordHasher hasher = new PasswordHasher();
        String hash = hasher.hash("correct-horse-battery");
        assertThat(hash).startsWith("$2");
        assertThat(hasher.matches("correct-horse-battery", hash)).isTrue();
        assertThat(hasher.matches("correct-horse-batterx", hash)).isFalse();
        assertThat(hasher.matches("a".repeat(100), hash)).isFalse();
        assertThat(hasher.matchesDummy("a".repeat(100))).isFalse();
        assertThat(hasher.matchesDummy(null)).isFalse();
    }

    @Test
    void over_long_password_costs_one_bcrypt_comparison_on_every_path() {
        java.util.concurrent.atomic.AtomicInteger matches = new java.util.concurrent.atomic.AtomicInteger();
        org.springframework.security.crypto.password.PasswordEncoder counting =
                new org.springframework.security.crypto.password.PasswordEncoder() {
                    @Override
                    public String encode(CharSequence raw) {
                        return "h";
                    }

                    @Override
                    public boolean matches(CharSequence raw, String encoded) {
                        matches.incrementAndGet();
                        return false;
                    }
                };
        PasswordHasher hasher = new PasswordHasher(counting);
        String tooLong = "a".repeat(73);
        // 帳號存在（有 hash）與不存在（matchesDummy）、hash 為 null、正常長度：都是剛好一次
        for (java.util.function.Supplier<Boolean> path : java.util.List.<java.util.function.Supplier<Boolean>>of(
                () -> hasher.matches(tooLong, "$2a$real"),
                () -> hasher.matchesDummy(tooLong),
                () -> hasher.matches(tooLong, null),
                () -> hasher.matches("short-but-ok-123", "$2a$real"),
                () -> hasher.matchesDummy("short-but-ok-123"))) {
            matches.set(0);
            assertThat(path.get()).isFalse();
            assertThat(matches.get()).isEqualTo(1);
        }
    }
}
