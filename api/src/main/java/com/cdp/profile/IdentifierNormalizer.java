package com.cdp.profile;

import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/** profiles.md §1.1：識別碼正規化（寫入與搜尋共用）。不合法回 {@link Optional#empty()}。 */
@Component
public class IdentifierNormalizer {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+$", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern PHONE = Pattern.compile("^\\+[1-9][0-9]{6,14}$");
    private static final Pattern COUNTRY_CODE = Pattern.compile("^[1-9][0-9]{0,2}$");
    private static final int MAX_OTHER_LENGTH = 200;

    private final String countryCode;

    public IdentifierNormalizer(PhoneProperties phone) {
        String code = phone.defaultCountryCode();
        if (code == null || !COUNTRY_CODE.matcher(code.strip()).matches()) {
            throw new IllegalStateException(
                    "CDP_PHONE_DEFAULT_COUNTRY_CODE 須為 1 到 3 位數字（不含 +、不以 0 開頭）");
        }
        this.countryCode = code.strip();
    }

    public Optional<String> normalize(IdentifierType type, String raw) {
        if (raw == null || raw.indexOf((char) 0) >= 0) {
            return Optional.empty();
        }
        String value =
                switch (type) {
                    case EMAIL -> email(raw);
                    case PHONE -> phone(raw);
                    default -> other(raw);
                };
        return Optional.ofNullable(value);
    }

    private static String email(String raw) {
        String v = raw.strip().toLowerCase(java.util.Locale.ROOT);
        return EMAIL.matcher(v).matches() ? v : null;
    }

    private String phone(String raw) {
        String v = raw.replaceAll("[\\s\\-().]", "");
        if (v.startsWith("0")) {
            v = "+" + countryCode + v.substring(1);
        }
        return PHONE.matcher(v).matches() ? v : null;
    }

    private static String other(String raw) {
        String v = raw.strip();
        int length = v.codePointCount(0, v.length());
        return length >= 1 && length <= MAX_OTHER_LENGTH ? v : null;
    }
}
