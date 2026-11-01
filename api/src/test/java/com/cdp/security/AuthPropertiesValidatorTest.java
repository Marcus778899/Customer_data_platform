package com.cdp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AuthPropertiesValidatorTest {

    private static AuthPropertiesValidator of(String i, String a) {
        return new AuthPropertiesValidator(new AuthProperties(i, a));
    }

    @Test
    void valid_passes() {
        assertThatCode(() -> of("i", "a").afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    void empty_or_same_fails_without_leaking_values() {
        assertThatThrownBy(() -> of(null, "a").afterPropertiesSet()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> of("i", " ").afterPropertiesSet()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> of("SAME-K", "SAME-K").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .satisfies(t -> assertThat(t.getMessage()).doesNotContain("SAME-K"));
    }
}
