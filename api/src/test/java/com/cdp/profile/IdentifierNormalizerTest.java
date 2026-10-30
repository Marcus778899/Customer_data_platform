package com.cdp.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IdentifierNormalizerTest {

    private final IdentifierNormalizer normalizer = new IdentifierNormalizer(new PhoneProperties("886"));

    @Test
    void email_isTrimmedLowercased_andMustHaveAt() {
        assertThat(normalizer.normalize(IdentifierType.EMAIL, "  Amy@Example.COM ")).contains("amy@example.com");
        assertThat(normalizer.normalize(IdentifierType.EMAIL, "no-at")).isEmpty();
        assertThat(normalizer.normalize(IdentifierType.EMAIL, "a b@example.com")).isEmpty();
        assertThat(normalizer.normalize(IdentifierType.EMAIL, "a　b@example.com")).isEmpty();
        assertThat(normalizer.normalize(IdentifierType.EMAIL, "a b@example.com")).isEmpty();
    }

    @Test
    void phone_stripsSeparators_andAddsDefaultCountryCode() {
        assertThat(normalizer.normalize(IdentifierType.PHONE, "0912-345 678")).contains("+886912345678");
        assertThat(normalizer.normalize(IdentifierType.PHONE, "+886 (912) 345.678")).contains("+886912345678");
        assertThat(normalizer.normalize(IdentifierType.PHONE, "abc")).isEmpty();
        assertThat(normalizer.normalize(IdentifierType.PHONE, "+123")).isEmpty();
    }

    @Test
    void others_areTrimmedCaseSensitive_1to200() {
        assertThat(normalizer.normalize(IdentifierType.MEMBER_ID, "  M-1 ")).contains("M-1");
        assertThat(normalizer.normalize(IdentifierType.DEVICE_ID, "   ")).isEmpty();
        assertThat(normalizer.normalize(IdentifierType.DEVICE_ID, "x".repeat(201))).isEmpty();
        assertThat(normalizer.normalize(IdentifierType.DEVICE_ID, "x".repeat(200))).isPresent();
    }

    @Test
    void invalidCountryCode_failsAtStartup() {
        assertThatThrownBy(() -> new IdentifierNormalizer(new PhoneProperties("CHANGE_ME")))
                .isInstanceOf(IllegalStateException.class);
    }
}
