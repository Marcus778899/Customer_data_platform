package com.cdp.journey;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 表 journey（旅程生命週期與草稿，spec 07 §12）。{@code lock}、寫入方法須在呼叫端的交易內執行。 */
public interface JourneyStore {

    /** 一列 journey。draft 為 definition（name、description、settings、entry_node、nodes）。 */
    record JourneyRow(
            String journeyId,
            JourneyStatus status,
            Integer latestVersion,
            Map<String, Object> draft,
            Map<String, Object> draftLayout,
            int draftRevision,
            String draftUpdatedBy,
            Instant draftUpdatedAt,
            Instant createdAt,
            Instant updatedAt,
            Instant stoppedAt,
            String stopReason,
            String stoppedBy) {}

    /** 停止後尚未結束的 Enrollment 與其旅程的停止原因。 */
    record StoppedEnrollment(String enrollmentId, String journeyId, int journeyVersion, String stopReason) {}

    Optional<JourneyRow> find(String journeyId);

    /** SELECT … FOR UPDATE。 */
    Optional<JourneyRow> lock(String journeyId);

    /** 新旅程（草稿骨架）。 */
    void insertDraftJourney(String journeyId, Map<String, Object> draft, String userId, Instant now);

    /** 沒有 journey 列的舊版旅程（只有 journey_definition）補一列；已存在則不動。 */
    void ensureRowForExisting(String journeyId, Instant now);

    /** 以 journey_definition 的最新版本為準建立 ACTIVE 的新列（journeys.md §3.1 新旅程）。 */
    void insertPublished(String journeyId, int version, Instant now);

    void saveDraft(
            String journeyId,
            int newRevision,
            Map<String, Object> definition,
            Map<String, Object> layout,
            String userId,
            Instant now);

    void clearDraft(String journeyId, Instant now);

    /** 發佈後：ACTIVE、latest_version；clearDraft 為真時一併清草稿（核准），否則保留（管理金鑰建立）。 */
    void markPublished(String journeyId, int version, boolean clearDraft, Instant now);

    /** 設 ABORTED 並把全部版本標 ABORTED（新事件不再進入）。 */
    void markAborted(String journeyId, String reason, String userId, Instant now);

    /** 在 journey_definition 建立新版本（status ACTIVE，version = max + 1），回傳版本號。 */
    int insertVersion(String journeyId, Map<String, Object> definition, Map<String, Object> layout, Instant now);

    boolean versionExists(String journeyId, int version);

    /** 某版本的畫布座標（沒有或版本不存在 = empty）。 */
    Optional<Map<String, Object>> findLayout(String journeyId, int version);

    boolean isAborted(String journeyId);

    /** 停止的旅程中尚未結束的 Enrollment（FOR UPDATE SKIP LOCKED，在交易內呼叫）。 */
    List<StoppedEnrollment> lockOpenEnrollmentsOfStoppedJourneys(int limit);

    /** field-keys：任取 limit 位客戶 attributes 的鍵聯集（字母序）。 */
    List<String> profileAttributeKeys(int limit);

    /** field-keys：該事件最近 limit 筆 properties 的鍵聯集（字母序）。 */
    List<String> eventPropertyKeys(String eventName, int limit);
}
