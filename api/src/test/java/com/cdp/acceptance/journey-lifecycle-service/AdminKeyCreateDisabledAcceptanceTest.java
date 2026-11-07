package com.cdp.acceptance.journeylifecycleservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import com.cdp.acceptance.security.AuthHttp.Resp;

/**
 * D-061：CDP_ADMIN_KEY_JOURNEY_CREATE=false 時，管理金鑰建立旅程版本回 403 ADMIN_KEY_JOURNEY_CREATE_DISABLED，不建版本、
 * 不寫紀錄；旅程仍可經草稿送審核准。設定屬性名稱未在契約定義，三種常見命名一併設定（對應 .env 變數的 relaxed binding）。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "cdp.engine.poll-interval=PT1H",
            "cdp.relay.poll-interval=PT1H",
            "cdp.relay.lease=PT10S",
            "cdp.security.admin-key-journey-create=false",
            "cdp.admin-key-journey-create=false",
            "cdp.journey.admin-key-create=false"
        })
class AdminKeyCreateDisabledAcceptanceTest extends LifecycleBase {

    @Test
    void keyCreate_isForbidden_noVersion_noRecord() throws Exception {
        String name = "停用金鑰-" + uniq();
        Map<String, Object> b = new LinkedHashMap<>(sendDef(name, "evt_" + uniq(), "PT1H"));
        Resp r = key("POST", "/api/v1/journeys", b);
        assertProblem(r, 403, "ADMIN_KEY_JOURNEY_CREATE_DISABLED");

        assertThat(jdbc.queryForObject("select count(*) from journey_definition where name = ?", Integer.class, name)).isZero();
        assertThat(ok(key("GET", "/api/v1/journeys?q=" + java.net.URLEncoder.encode(name, "UTF-8"), null), 200).path("items"))
                .isEmpty();
    }

    @Test
    void keyCreate_onExistingJourney_isForbidden_andWritesNoReview() throws Exception {
        Actor editor = actor("EDITOR");
        Actor admin = actor("ADMIN");
        String ev = "evt_" + uniq();
        String id = publishNew(editor, admin, sendDef("停用-既有-" + uniq(), ev, "PT1H"));
        int before = reviews(id).size();

        Map<String, Object> b = new LinkedHashMap<>();
        b.put("journey_id", id);
        b.putAll(sendDef("停用-既有-新", ev, "PT2H"));
        assertProblem(key("POST", "/api/v1/journeys", b), 403, "ADMIN_KEY_JOURNEY_CREATE_DISABLED");

        assertThat(reviews(id)).hasSize(before);
        assertThat(getDetail(id).path("latest_version").asInt()).isEqualTo(1);
        assertThat(getDetail(id).path("versions")).hasSize(1);
    }

    @Test
    void draftSubmitApprove_stillWorks() throws Exception {
        Actor editor = actor("EDITOR");
        Actor admin = actor("ADMIN");
        String id = publishNew(editor, admin, sendDef("停用-走審核-" + uniq(), "evt_" + uniq(), "PT1H"));
        assertThat(getDetail(id).path("status").asString()).isEqualTo("ACTIVE");
        assertThat(reviews(id)).hasSize(1);
        assertThat(reviews(id).get(0).path("reason").isNull() || !"管理金鑰建立".equals(reviews(id).get(0).path("reason").asString()))
                .isTrue();
    }
}
