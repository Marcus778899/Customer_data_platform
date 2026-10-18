package com.cdp.acceptance.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** FIX-03a 驗收：喚醒時缺定義或缺 open trace → FAILED＋decision_reason，不丟例外、不影響同批其他筆。 */
class EnrollmentWakeMissingDataAcceptanceTest extends IngestAcceptanceBase {

    private String enrollmentId(String profileId) {
        return jdbc.queryForObject(
                "select enrollment_id from enrollment_record where profile_id = ?",
                String.class,
                profileId);
    }

    private String status(String profileId) {
        return jdbc.queryForObject(
                "select status from enrollment_record where profile_id = ?",
                String.class,
                profileId);
    }

    private List<Map<String, Object>> trace(String enrollmentId) {
        return jdbc.queryForList(
                "select * from node_trace where enrollment_id = ? order by seq", enrollmentId);
    }

    @Test
    void definitionDeletedBeforeWake_failsWithReason_andOthersInSameBatchComplete()
            throws Exception {
        String evA = newEventName();
        String journeyA = createJourney(evA);
        String evB = newEventName();
        createJourney(evB);
        String pA = ulid();
        String pB = ulid();
        ingestOk(evA, pA);
        ingestOk(evB, pB);
        dispatcher.runOnce();
        assertThat(status(pA)).isEqualTo("WAITING");
        assertThat(status(pB)).isEqualTo("WAITING");

        jdbc.update("delete from journey_definition where journey_id = ?", journeyA);
        clock.advance(Duration.ofHours(1).plusMinutes(1));

        assertThatCode(() -> advanceLoop.runOnce()).doesNotThrowAnyException();

        assertThat(status(pA)).isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForObject(
                                "select terminated_at is not null from enrollment_record"
                                        + " where profile_id = ?",
                                Boolean.class,
                                pA))
                .isTrue();
        List<Map<String, Object>> trace = trace(enrollmentId(pA));
        assertThat(trace).isNotEmpty();
        Map<String, Object> last = trace.get(trace.size() - 1);
        assertThat(String.valueOf(last.get("decision_reason"))).isNotBlank();
        assertThat(last.get("exited_at")).isNotNull();

        assertThat(status(pB)).as("同批其他筆不受影響").isEqualTo("COMPLETED");

        // FAILED 為終態，不會再被撿起
        assertThatCode(() -> advanceLoop.runOnce()).doesNotThrowAnyException();
        assertThat(status(pA)).isEqualTo("FAILED");
    }

    @Test
    void openTraceMissingBeforeWake_failsWithReason_andOthersInSameBatchComplete()
            throws Exception {
        String evA = newEventName();
        createJourney(evA);
        String evB = newEventName();
        createJourney(evB);
        String pA = ulid();
        String pB = ulid();
        ingestOk(evA, pA);
        ingestOk(evB, pB);
        dispatcher.runOnce();

        jdbc.update(
                "delete from node_trace where enrollment_id = ? and exited_at is null",
                enrollmentId(pA));
        clock.advance(Duration.ofHours(1).plusMinutes(1));

        assertThatCode(() -> advanceLoop.runOnce()).doesNotThrowAnyException();

        assertThat(status(pA)).isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForObject(
                                "select termination_reason from enrollment_record"
                                        + " where profile_id = ?",
                                String.class,
                                pA))
                .isNotBlank();
        assertThat(status(pB)).isEqualTo("COMPLETED");
    }
}
