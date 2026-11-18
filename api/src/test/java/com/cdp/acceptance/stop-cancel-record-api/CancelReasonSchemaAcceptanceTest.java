package com.cdp.acceptance.stopcancelrecordapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** spec 07 §12「V10」：delivery_task.cancel_reason 可 null，CHECK 限只出現在 CANCELLED 且值為兩種之一。 */
class CancelReasonSchemaAcceptanceTest extends StopCancelBase {

    private void insert(String status, String skipReason, String cancelReason) {
        String key = "v10:" + uniq();
        jdbc.update(
                "insert into delivery_task (idempotency_key, enrollment_id, journey_id, journey_version, node_id,"
                        + " visit_seq, template_id, channel_id, channel_type, status, skip_reason, cancel_reason,"
                        + " attempted_at, created_at, updated_at)"
                        + " values (?, 'e', 'j', 1, 'send_1', 1, 't', 'c', 'MOCK', ?, ?, ?, now(), now(), now())",
                key,
                status,
                skipReason,
                cancelReason);
    }

    @Test
    void columnExists_nullable_andAcceptsNull() {
        assertThat(jdbc.queryForList(
                        "select is_nullable from information_schema.columns"
                                + " where table_name = 'delivery_task' and column_name = 'cancel_reason'",
                        String.class))
                .containsExactly("YES");
        insert("SENT", null, null);
        insert("CANCELLED", null, null); // V10 前的舊資料：原因未記錄
    }

    @Test
    void cancelled_acceptsBothReasons() {
        insert("CANCELLED", null, "JOURNEY_STOPPED");
        insert("CANCELLED", null, "ENROLLMENT_ENDED");
    }

    @Test
    void cancelled_rejectsUnknownReason() {
        assertThatThrownBy(() -> insert("CANCELLED", null, "WHATEVER"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void reasonOnlyAllowedOnCancelled() {
        assertThatThrownBy(() -> insert("SENT", null, "JOURNEY_STOPPED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("PENDING", null, "ENROLLMENT_ENDED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("SKIPPED", "CONSENT", "JOURNEY_STOPPED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
