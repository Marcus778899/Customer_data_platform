package com.cdp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AuthPropertiesValidatorTest {

    private static final String K1 = "a".repeat(32);
    private static final String K2 = "b".repeat(32);
    private static final String SAME = "s".repeat(40);

    private static AuthPropertiesValidator of(String i, String a) {
        return new AuthPropertiesValidator(new AuthProperties(i, a));
    }

    @Test
    void valid_passes() {
        assertThatCode(() -> of(K1, K2).afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    void empty_or_same_fails_without_leaking_values() {
        assertThatThrownBy(() -> of(null, K2).afterPropertiesSet()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> of(K1, " ").afterPropertiesSet()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> of(SAME, SAME).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .satisfies(t -> assertThat(t.getMessage()).doesNotContain(SAME));
    }

    @Test
    void weak_or_placeholder_keys_fail_naming_the_variable_without_value() {
        assertThatThrownBy(() -> of("a".repeat(31), K2).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CDP_INGEST_API_KEY");
        assertThatThrownBy(() -> of(K1, "  " + "b".repeat(20) + " ".repeat(20)).afterPropertiesSet())
                .hasMessageContaining("CDP_ADMIN_API_KEY");
        assertThatThrownBy(() -> of("xx-ChAnGe_Me-" + "0".repeat(30), K2).afterPropertiesSet())
                .hasMessageContaining("CDP_INGEST_API_KEY")
                .satisfies(t -> assertThat(t.getMessage()).doesNotContain("0000"));
        assertThatCode(() -> of("a".repeat(32), "b".repeat(32)).afterPropertiesSet()).doesNotThrowAnyException();
    }
}
