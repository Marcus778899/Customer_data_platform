package com.cdp.profile;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** cdp.phone.default-country-code：手機號碼以 0 開頭時補上的國碼，只含數字（CDP_PHONE_DEFAULT_COUNTRY_CODE；profiles.md §1.1）。 */
@ConfigurationProperties("cdp.phone")
public record PhoneProperties(String defaultCountryCode) {}
