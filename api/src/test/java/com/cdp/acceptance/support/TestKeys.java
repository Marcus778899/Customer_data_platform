package com.cdp.acceptance.support;

/**
 * 驗收測試共用的 API 金鑰常數（auth.md §1 金鑰強度：去空白後 >= 32 字、不含 CHANGE_ME、彼此不同）。
 * 低熵的測試假值，非機密。{@code application-test.yml} 的 {@code cdp.security.ingest-api-key}／{@code admin-api-key}
 * 必須與這裡的 {@link #INGEST} 與 {@link #ADMIN} 逐字相同（由 backend 在 security-hardening-api 同步）。
 */
public final class TestKeys {

    /** 攝取金鑰（38 字）。 */
    public static final String INGEST = "test-ingest-key-0000000000000000000000";

    /** 管理金鑰（38 字）。 */
    public static final String ADMIN = "test-admin-key-00000000000000000000000";

    /** 另一組合法的金鑰，供啟動檢查測試覆蓋用（各 >= 32 字、彼此不同）。 */
    public static final String VALID_A = "valid-key-aaaa-000000000000000000000";

    public static final String VALID_B = "valid-key-bbbb-000000000000000000000";

    private TestKeys() {}
}
