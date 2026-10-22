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
}
