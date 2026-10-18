package com.cdp.acceptance.common;

import static org.hamcrest.Matchers.isA;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cdp.acceptance.engine.EngineTestSupport;

/**
 * FIX-01 驗收：Spring MVC 內建例外也要依契約 §5 輸出（problem+json、code、request_id），
 * 且不外洩例外類別名或堆疊。登入 415 見 §1.2。
 */
@SpringBootTest(properties = {
            "cdp.engine.poll-interval=PT1H",
            "cdp.relay.poll-interval=PT1H",
            "spring.rabbitmq.listener.simple.auto-startup=false"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({EngineTestSupport.class, FrameworkErrorFormatAcceptanceTest.ThrowingControllerConfig.class})
class FrameworkErrorFormatAcceptanceTest {

    private static final List<String> CONTRACT_CODES = List.of(
            "VALIDATION_FAILED", "BATCH_TOO_LARGE", "UNAUTHENTICATED", "INVALID_CREDENTIALS",
            "FORBIDDEN", "UNSUPPORTED_MEDIA_TYPE", "ENROLLMENT_NOT_FOUND", "JOURNEY_NOT_FOUND",
            "NOT_FOUND", "METHOD_NOT_ALLOWED", "NOT_ACCEPTABLE", "PAYLOAD_TOO_LARGE",
            "INTERNAL_ERROR", "SERVICE_UNAVAILABLE");

    @Autowired MockMvc mvc;

    // application-test.yml 的測試金鑰（cdp.security.*）
    private static final String INGEST_KEY = "test-ingest-key";
    private static final String ADMIN_KEY = "test-admin-key";

    /** 同時帶 Bearer（BE-04 後）與 mock user（BE-04 前沿用 Boot 預設安全設定），同 JourneyEngine 驗收做法。 */
    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
        return authed(b, INGEST_KEY);
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b, String key) {
        return b.header("Authorization", "Bearer " + key)
                .with(user("qa").roles("ADMIN", "INGEST"))
                .with(csrf());
    }

    private ResultActions baseShape(ResultActions r, int status) throws Exception {
        return r.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.code", isA(String.class)))
                .andExpect(jsonPath("$.code", org.hamcrest.Matchers.in(CONTRACT_CODES)))
                .andExpect(jsonPath("$.request_id", notNullValue()))
                .andExpect(jsonPath("$.request_id").isNotEmpty());
    }

    private static void noLeak(ResultActions r) throws Exception {
        String body = r.andReturn().getResponse().getContentAsString();
        for (String bad : List.of("Exception", "com.cdp", "org.springframework", "tools.jackson",
                "com.fasterxml", "java.lang", "\tat ", "stackTrace", "trace")) {
            org.assertj.core.api.Assertions.assertThat(body).doesNotContain(bad);
        }
    }

    @Test
    void ingestUnsupportedContentType_415() throws Exception {
        ResultActions r = mvc.perform(authed(post("/api/v1/events"))
                .contentType(MediaType.TEXT_PLAIN)
                .content("hello"));
        baseShape(r, 415).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        noLeak(r);
    }

    @Test
    void unsupportedContentType_requestIdMatchesHeader() throws Exception {
        ResultActions r = mvc.perform(authed(post("/api/v1/events"))
                .header("X-Request-Id", "fix01-req.1")
                .contentType(MediaType.TEXT_PLAIN)
                .content("hello"));
        baseShape(r, 415)
                .andExpect(header().string("X-Request-Id", "fix01-req.1"))
                .andExpect(jsonPath("$.request_id").value("fix01-req.1"));
    }

    @Test
    void loginUnsupportedContentType_415() throws Exception {
        // §1.2：login 不需認證；csrf() 僅為 BE-04 前的 Boot 預設安全設定
        ResultActions r = mvc.perform(post("/api/v1/auth/login").with(csrf()).with(user("qa").roles("ADMIN"))
                .contentType(MediaType.TEXT_PLAIN)
                .content("username=a&password=b"));
        baseShape(r, 415).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        noLeak(r);
    }

    @Test
    void malformedJson_400ValidationFailed() throws Exception {
        ResultActions r = mvc.perform(authed(post("/api/v1/events"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"identity\": {bad json"));
        baseShape(r, 400)
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0].field").isNotEmpty())
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
        noLeak(r);
    }

    @Test
    void malformedJsonOnLogin_400ValidationFailed() throws Exception {
        ResultActions r = mvc.perform(post("/api/v1/auth/login").with(csrf()).with(user("qa").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not json"));
        baseShape(r, 400).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        noLeak(r);
    }

    @Test
    void unknownRoute_404NotFound() throws Exception {
        ResultActions r = mvc.perform(authed(get("/api/v1/no-such-route"), ADMIN_KEY));
        baseShape(r, 404).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        noLeak(r);
    }

    @Test
    void wrongHttpMethod_405MethodNotAllowedWithAllowHeader() throws Exception {
        ResultActions r = mvc.perform(authed(get("/api/v1/events")));
        baseShape(r, 405)
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("POST")))
                .andExpect(jsonPath("$.errors").doesNotExist());
        noLeak(r);
    }

    @Test
    void unacceptable_406NotAcceptableStillProblemJson() throws Exception {
        ResultActions r = mvc.perform(authed(get("/api/v1/enrollments").param("q", "x"), ADMIN_KEY)
                .accept(MediaType.APPLICATION_XML));
        baseShape(r, 406)
                .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"))
                .andExpect(jsonPath("$.errors").doesNotExist());
        noLeak(r);
    }

    @Test
    void unsupportedContentType_415HasAcceptHeaderAndNoErrors() throws Exception {
        ResultActions r = mvc.perform(authed(post("/api/v1/events"))
                .contentType(MediaType.TEXT_PLAIN)
                .content("hello"));
        baseShape(r, 415)
                .andExpect(header().string("Accept", org.hamcrest.Matchers.containsString("application/json")))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void unknownRoute_404HasNoErrors() throws Exception {
        mvc.perform(authed(get("/api/v1/no-such-route"), ADMIN_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void accessDeniedThrownInController_403ForbiddenNot500() throws Exception {
        ResultActions r = mvc.perform(authed(get("/api/v1/test-only/access-denied"), ADMIN_KEY));
        baseShape(r, 403).andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.errors").doesNotExist());
        noLeak(r);
    }

    @Test
    void authenticationExceptionThrownInController_401Not500() throws Exception {
        ResultActions r = mvc.perform(authed(get("/api/v1/test-only/auth-failure"), ADMIN_KEY));
        baseShape(r, 401).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.errors").doesNotExist());
        noLeak(r);
    }

    /** 只在測試存在的 controller，用來觸發 Security 例外。 */
    @TestConfiguration
    static class ThrowingControllerConfig {
        @Bean
        ThrowingController throwingController() {
            return new ThrowingController();
        }
    }

    @RestController
    static class ThrowingController {
        @GetMapping("/api/v1/test-only/access-denied")
        String denied() {
            throw new AccessDeniedException("nope");
        }

        @GetMapping("/api/v1/test-only/auth-failure")
        String authFailure() {
            throw new BadCredentialsException("nope");
        }
    }
}
