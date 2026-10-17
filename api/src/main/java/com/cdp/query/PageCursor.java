package com.cdp.query;

import java.time.Instant;

/** 鍵集分頁游標 (時間, id)；對外為不透明字串（base64url）。 */
public record PageCursor(Instant at, String id) {

    public String encode() {
        throw new UnsupportedOperationException("TODO(BE-03)");
    }

    /** 格式錯誤丟 ApiException(VALIDATION_FAILED, field=cursor)。 */
    public static PageCursor decode(String cursor) {
        throw new UnsupportedOperationException("TODO(BE-03)");
    }
}
