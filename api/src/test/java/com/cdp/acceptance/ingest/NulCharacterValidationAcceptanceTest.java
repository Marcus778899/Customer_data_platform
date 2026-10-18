package com.cdp.acceptance.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** FIX-03a 驗收：含 U+0000 的字串 → 400 VALIDATION_FAILED，errors 指出欄位（jsonb 不收 U+0000）。 */
class NulCharacterValidationAcceptanceTest extends IngestAcceptanceBase {

    private static final String NUL = String.valueOf((char) 0);

    private Map<String, Object> eventWith(String id) {
        return event(id, newEventName(), ulid());
    }

    private int eventRows(String id) {
        return jdbc.queryForObject(
                "select count(*) from event where event_id = ?", Integer.class, id);
    }

    @Test
    void nulInPropertyValue_isRejected_withFieldPath() throws Exception {
        String id = ulid();
        Map<String, Object> e = eventWith(id);
        e.put("properties", Map.of("note", "ab" + NUL + "cd"));
        ingest(e)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", hasItem(startsWith("properties"))));
        assertThat(eventRows(id)).isZero();
    }

    @Test
    void nulInNestedPropertyValueAndKey_isRejected() throws Exception {
        Map<String, Object> nested = eventWith(ulid());
        nested.put("properties", Map.of("outer", Map.of("inner", List.of("ok", "x" + NUL))));
        ingest(nested)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", hasItem(startsWith("properties"))));

        Map<String, Object> key = eventWith(ulid());
        key.put("properties", Map.of("k" + NUL, "v"));
        ingest(key)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", hasItem(startsWith("properties"))));
    }

    @Test
    void nulInOtherStringFields_isRejected_withThatField() throws Exception {
        Map<String, Object> src = eventWith(ulid());
        src.put("source_id", "web" + NUL);
        ingest(src)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("source_id")));

        Map<String, Object> profile = eventWith(ulid());
        profile.put("identity", Map.of("profile_id", "p" + NUL));
        ingest(profile)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("identity.profile_id")));
    }

    @Test
    void batch_rejectsOnlyTheEventWithNul() throws Exception {
        String okId = ulid();
        String badId = ulid();
        Map<String, Object> ok = eventWith(okId);
        Map<String, Object> bad = eventWith(badId);
        bad.put("properties", Map.of("note", "a" + NUL));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("events", List.of(ok, bad));

        postJson("/api/v1/events/batch", body, "test-ingest-key")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.results[1].event_id").value(badId))
                .andExpect(
                        jsonPath("$.results[1].errors[*].field", hasItem(containsString("properties"))));
        assertThat(eventRows(okId)).isEqualTo(1);
        assertThat(eventRows(badId)).isZero();
    }
}
