package com.cdp.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.cdp.common.web.ApiException;
import com.cdp.common.web.ErrorCode;

class PageCursorTest {

    @Test
    void roundTrip() {
        PageCursor c = new PageCursor(Instant.parse("2026-10-18T02:00:00.123456Z"), "01JABC|x");
        assertThat(PageCursor.decode(c.encode())).isEqualTo(c);
    }

    @Test
    void malformedIsValidationFailed() {
        for (String bad : new String[] {"!!not-a-cursor", "", "YWJj"}) {
            assertThatThrownBy(() -> PageCursor.decode(bad))
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
    }

    @Test
    void decodedNulIsValidationFailed() {
        String nul = new PageCursor(Instant.parse("2026-01-01T00:00:00Z"), "a" + (char) 0 + "b").encode();
        assertThatThrownBy(() -> PageCursor.decode(nul))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
