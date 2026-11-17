package com.cdp.engine.runtime;

import java.time.Instant;
import java.util.Optional;

/** 重新進入判斷用的查詢（spec 02 §6「落地」）。由 JdbcStateStore 實作；全部須在 start 的交易內呼叫。 */
public interface ReentryStore {

    /** 取 pg_advisory_xact_lock（鍵 = journey_id＋profile_id），交易結束才釋放；同一客戶的併發觸發因此排隊。 */
    void lockProfile(String journeyId, String profileId);

    /** 同一事件（trigger_event_id）已建立的 Enrollment；事件重派發的冪等判斷。 */
    Optional<String> findByTriggerEvent(String journeyId, String triggerEventId);

    /** 冷卻期內擋人的 Enrollment：未結束，或 terminated_at 晚於 cutoff（= 現在 − 冷卻期）。取最近一筆。 */
    Optional<String> findCooldownBlocker(String journeyId, String profileId, Instant cutoff);
}
