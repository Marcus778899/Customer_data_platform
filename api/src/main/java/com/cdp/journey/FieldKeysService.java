package com.cdp.journey;

import java.time.Clock;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cdp.common.web.ApiException;
import com.cdp.common.web.ErrorCode;
import com.cdp.common.web.FieldError;

/** journeys.md §4 {@code field-keys}：條件欄位建議清單（只是建議，不保證完整）。 */
@Service
public class FieldKeysService {

    static final int SAMPLE = 1000;

    private final JourneyStore journeys;
    private final Clock clock;

    public FieldKeysService(JourneyStore journeys, Clock clock) {
        this.journeys = journeys;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public FieldKeys fieldKeys(String eventName) {
        if (eventName != null && (eventName.length() > 200 || eventName.indexOf((char) 0) >= 0)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "請求參數不正確。",
                    List.of(new FieldError("event_name", "不可超過 200 字，也不可含 NUL 字元")));
        }
        List<String> attributes = journeys.profileAttributeKeys(SAMPLE);
        List<String> properties =
                eventName == null || eventName.isBlank() ? List.of() : journeys.eventPropertyKeys(eventName, SAMPLE);
        return new FieldKeys(attributes, properties, clock.instant());
    }
}
