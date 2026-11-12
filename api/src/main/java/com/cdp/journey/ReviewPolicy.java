package com.cdp.journey;

import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.cdp.common.web.ApiException;
import com.cdp.common.web.ErrorCode;

/**
 * 審核規則的唯一實作（journeys.md §3.3；D-060、D-061）：不可核准自己送的、撤回權限、
 * 管理金鑰建立版本的開關與紀錄。
 */
@Component
public class ReviewPolicy {

    static final String ADMIN_KEY_REASON = "管理金鑰建立";
    static final String STOPPED_REASON = "旅程已停止";

    private final boolean adminKeyCreateAllowed;

    public ReviewPolicy(@Value("${cdp.security.admin-key-journey-create:true}") boolean adminKeyCreateAllowed) {
        this.adminKeyCreateAllowed = adminKeyCreateAllowed;
    }

    /** 規則 3：關閉時管理金鑰不可建立版本。 */
    public void checkAdminKeyCreate() {
        if (!adminKeyCreateAllowed) {
            throw new ApiException(
                    ErrorCode.ADMIN_KEY_JOURNEY_CREATE_DISABLED, "此環境不允許以管理金鑰建立旅程版本，請改由草稿送審。");
        }
    }

    /** 規則 1：送審者不可核准自己的 Review（退回、撤回不受限）。 */
    public void checkApprove(JourneyReview review, Caller caller) {
        if (review.submittedBy() != null
                && caller.userId() != null
                && review.submittedBy().userId().equals(caller.userId())) {
            throw new ApiException(ErrorCode.SELF_APPROVAL_FORBIDDEN, "不可核准自己送審的旅程，請由另一位管理員核准。");
        }
    }

    /** 撤回：送審者本人或 ADMIN。 */
    public void checkWithdraw(JourneyReview review, Caller caller) {
        boolean submitter =
                review.submittedBy() != null
                        && caller.userId() != null
                        && review.submittedBy().userId().equals(caller.userId());
        if (!submitter && !caller.admin()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "只有送審者或管理員可以撤回。");
        }
    }

    /** 規則 3：管理金鑰建立版本的同一交易寫一筆已核准的紀錄。 */
    public JourneyReview adminKeyRecord(String reviewId, String journeyId, int version, Instant now) {
        return new JourneyReview(
                reviewId,
                journeyId,
                JourneyReview.PUBLISH,
                ReviewStatus.APPROVED,
                null,
                null,
                null,
                null,
                now,
                null,
                now,
                ADMIN_KEY_REASON,
                version);
    }
}
