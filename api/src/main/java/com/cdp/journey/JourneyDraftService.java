package com.cdp.journey;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cdp.common.IdGenerator;
import com.cdp.common.web.ApiException;
import com.cdp.common.web.ErrorCode;
import com.cdp.common.web.FieldError;
import com.cdp.journey.JourneyStore.JourneyRow;

import tools.jackson.databind.json.JsonMapper;

/** journeys.md §3：草稿的建立、讀取、存檔（只驗結構）、刪除與 validate。 */
@Service
public class JourneyDraftService {

    static final int MAX_NODES = 200;
    static final int MAX_BYTES = 256 * 1024;
    static final int MAX_NAME = 100;

    private final JourneyStore journeys;
    private final JourneyReviewStore reviews;
    private final JourneyGraphValidator validator;
    private final UserRefs users;
    private final IdGenerator ids;
    private final Clock clock;
    private final JsonMapper json;

    public JourneyDraftService(
            JourneyStore journeys,
            JourneyReviewStore reviews,
            JourneyGraphValidator validator,
            UserRefs users,
            IdGenerator ids,
            Clock clock,
            JsonMapper json) {
        this.journeys = journeys;
        this.reviews = reviews;
        this.validator = validator;
        this.users = users;
        this.ids = ids;
        this.clock = clock;
        this.json = json;
    }

    @Transactional
    public DraftCreatedResponse create(CreateDraftRequest request, Caller caller) {
        String name = request == null ? null : request.name();
        if (name == null || name.isBlank()) {
            throw invalid("name", "必填");
        }
        if (name.length() > MAX_NAME) {
            throw invalid("name", "不可超過 " + MAX_NAME + " 字");
        }
        if (name.indexOf((char) 0) >= 0) {
            throw invalid("name", "不可含 NUL 字元");
        }
        String journeyId = ids.newId();
        Map<String, Object> skeleton = new LinkedHashMap<>();
        skeleton.put("name", name);
        skeleton.put("description", null);
        skeleton.put("settings", Map.of());
        skeleton.put("entry_node", "start");
        Map<String, Object> start = new LinkedHashMap<>();
        start.put("id", "start");
        start.put("type", "StartNode");
        start.put("name", null);
        start.put("config", Map.of());
        start.put("outputs", Map.of());
        skeleton.put("nodes", List.of(start));
        journeys.insertDraftJourney(journeyId, skeleton, caller.userId(), clock.instant());
        return new DraftCreatedResponse(journeyId, JourneyStatus.DRAFT, 1);
    }

    @Transactional(readOnly = true)
    public Draft get(String journeyId) {
        JourneyRow row = journeys.find(journeyId).orElseThrow(JourneyDraftService::journeyNotFound);
        if (row.draft() == null) {
            throw new ApiException(ErrorCode.DRAFT_NOT_FOUND, "這個旅程目前沒有草稿。");
        }
        return toDraft(row);
    }

    @Transactional
    public Draft save(String journeyId, SaveDraftRequest request, Caller caller) {
        Map<String, Object> definition = checkStructure(request);
        @SuppressWarnings("unchecked")
        Map<String, Object> layout = (Map<String, Object>) request.layout();
        JourneyRow row = journeys.lock(journeyId).orElseThrow(JourneyDraftService::journeyNotFound);
        if (row.status() == JourneyStatus.ABORTED) {
            throw aborted();
        }
        if (reviews.findPending(journeyId).isPresent()) {
            throw inReview();
        }
        int current = row.draft() == null ? 0 : row.draftRevision();
        if (request.revision() != current) {
            throw new ApiException(
                    ErrorCode.DRAFT_CONFLICT, "草稿已被更新（目前版次 " + current + "），請重新載入後再存檔。");
        }
        Instant now = clock.instant();
        journeys.saveDraft(journeyId, current + 1, definition, layout, caller.userId(), now);
        return toDraft(journeys.find(journeyId).orElseThrow());
    }

    @Transactional
    public void delete(String journeyId) {
        JourneyRow row = journeys.lock(journeyId).orElseThrow(JourneyDraftService::journeyNotFound);
        if (reviews.findPending(journeyId).isPresent()) {
            throw inReview();
        }
        if (row.status() == JourneyStatus.DRAFT) {
            throw new ApiException(ErrorCode.DRAFT_REQUIRED, "尚未發佈的旅程不可刪除草稿。");
        }
        if (row.draft() != null) {
            journeys.clearDraft(journeyId, clock.instant());
        }
    }

    @Transactional(readOnly = true)
    public ValidationReport validate(String journeyId) {
        JourneyRow row = journeys.find(journeyId).orElseThrow(JourneyDraftService::journeyNotFound);
        if (row.draft() == null) {
            throw new ApiException(ErrorCode.DRAFT_NOT_FOUND, "這個旅程目前沒有草稿。");
        }
        return validator.validate(row.draft());
    }

    // ---------- 內部 ----------

    private Draft toDraft(JourneyRow row) {
        JourneyReview latest =
                reviews.latest(row.journeyId()).filter(r -> r.status() != ReviewStatus.APPROVED).orElse(null);
        return new Draft(
                row.journeyId(),
                row.draftRevision(),
                row.draft(),
                row.draftLayout(),
                row.draftUpdatedAt() == null ? row.updatedAt() : row.draftUpdatedAt(),
                users.of(row.draftUpdatedBy()),
                latest);
    }

    /** PUT 只驗結構：definition 是物件、nodes 是陣列且 ≤ 200、整包 ≤ 256 KB；回傳 definition。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> checkStructure(SaveDraftRequest request) {
        if (request == null || request.revision() == null || request.revision() < 0) {
            throw invalid("revision", "必填，須為 0 以上的整數");
        }
        if (!(request.definition() instanceof Map<?, ?> definition)) {
            throw invalid("definition", "須為物件");
        }
        if (!(definition.get("nodes") instanceof List<?> nodes)) {
            throw invalid("definition.nodes", "須為陣列");
        }
        if (nodes.size() > MAX_NODES) {
            throw invalid("definition.nodes", "節點不可超過 " + MAX_NODES + " 個");
        }
        if (request.layout() != null && !(request.layout() instanceof Map)) {
            throw invalid("layout", "須為物件或 null");
        }
        int size = json.writeValueAsBytes(request.definition()).length;
        if (request.layout() != null) {
            size += json.writeValueAsBytes(request.layout()).length;
        }
        if (size > MAX_BYTES) {
            throw invalid("definition", "內容不可超過 256 KB");
        }
        return (Map<String, Object>) definition;
    }

    static ApiException invalid(String field, String message) {
        return new ApiException(
                ErrorCode.VALIDATION_FAILED, "請求內容不正確。", List.of(new FieldError(field, message)));
    }

    static ApiException journeyNotFound() {
        return new ApiException(ErrorCode.JOURNEY_NOT_FOUND, "找不到這個旅程。");
    }

    static ApiException aborted() {
        return new ApiException(ErrorCode.JOURNEY_ABORTED, "這個旅程已停止，不能再修改。");
    }

    static ApiException inReview() {
        return new ApiException(ErrorCode.DRAFT_IN_REVIEW, "草稿送審中，請先撤回再修改。");
    }
}
