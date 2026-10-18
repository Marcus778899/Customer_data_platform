package com.cdp.query;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import com.cdp.common.web.ApiException;
import com.cdp.common.web.ErrorCode;
import com.cdp.common.web.FieldError;

/** 鍵集分頁游標 (時間, id)；對外為不透明字串（base64url）。 */
public record PageCursor(Instant at, String id) {

    public String encode() {
        String raw = at.toString() + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 格式錯誤丟 ApiException(VALIDATION_FAILED, field=cursor)。 */
    public static PageCursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int bar = raw.indexOf('|');
            if (bar <= 0 || bar == raw.length() - 1) {
                throw new IllegalArgumentException("cursor");
            }
            return new PageCursor(Instant.parse(raw.substring(0, bar)), raw.substring(bar + 1));
        } catch (RuntimeException e) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "請求參數不正確。",
                    List.of(new FieldError("cursor", "游標格式錯誤")));
        }
    }
}
