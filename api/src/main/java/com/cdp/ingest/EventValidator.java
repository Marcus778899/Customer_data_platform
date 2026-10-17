package com.cdp.ingest;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;

import com.cdp.common.web.FieldError;

/** 01 §3、§4、§6 的欄位規則（v0.1.0 子集見契約 §3.2）；回傳空清單表示通過。 */
@Component
public class EventValidator {

    public List<FieldError> validate(EventRequest event, Instant receivedAt) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }
}
