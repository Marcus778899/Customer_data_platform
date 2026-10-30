package com.cdp.profile;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.cdp.common.web.ApiException;
import com.cdp.common.web.ErrorCode;
import com.cdp.common.web.FieldError;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 客戶資料的唯一寫入入口（DESIGN-03 做法 #4）：屬性淺層合併、識別碼正規化與衝突判定都在這裡。寫客戶表與識別碼表的 SQL
 * 不可出現在其他類別。
 */
@Component
public class ProfileWriter {

    static final Pattern ULID = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");
    private static final Pattern ATTRIBUTE_KEY = Pattern.compile("^[a-z][a-z0-9_]*$");
    private static final int MAX_ATTRIBUTES_BYTES = 32 * 1024;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final IdentifierNormalizer normalizer;
    private final Clock clock;

    public ProfileWriter(
            JdbcClient jdbc, JsonMapper json, IdentifierNormalizer normalizer, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.normalizer = normalizer;
        this.clock = clock;
    }

    /** 事件攝取用（profiles.md §2 末條）：不存在才建空白客戶，已存在不動（含 updated_at）。 */
    @Transactional
    public void ensureExists(String profileId) {
        insertBlank(profileId, clock.instant());
    }

    /** profiles.md §2：不存在則建立；整筆在一個交易，任何驗證失敗或識別碼衝突都不寫入。 */
    @Transactional
    public void upsert(String profileId, ProfileWriteRequest request) {
        List<FieldError> errors = new ArrayList<>();
        if (profileId == null || !ULID.matcher(profileId).matches()) {
            errors.add(new FieldError("profile_id", "須為 ULID（26 字元）"));
        }
        Map<String, Object> patch = request == null ? null : request.attributes();
        validateAttributeKeys(patch, errors);
        TreeSet<IdentifierKey> identifiers = normalizeIdentifiers(request, errors);
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }

        Instant now = clock.instant();
        insertBlank(profileId, now);
        // 同一客戶的併發寫入在這裡排隊
        String current =
                jdbc.sql("select attributes::text from profile where profile_id = :id for update")
                        .param("id", profileId)
                        .query(String.class)
                        .single();
        Map<String, Object> existing = json.readValue(current, MAP);
        Map<String, Object> merged = merge(existing, patch);
        String mergedJson = json.writeValueAsString(merged);
        if (mergedJson.getBytes(StandardCharsets.UTF_8).length > MAX_ATTRIBUTES_BYTES) {
            throw tooLarge();
        }
        if (mergedJson.contains("\\u0000")) {
            throw invalid(List.of(new FieldError("attributes", "不可含 NUL 字元")));
        }

        boolean changed = !merged.equals(existing);
        for (IdentifierKey key : identifiers) {
            changed |= claim(profileId, key, now);
        }
        // 內容沒變就不推進 updated_at（architect 同意）；新建的客戶 updated_at 已是現在
        if (changed) {
            try {
                jdbc.sql(
                                "update profile set attributes = cast(:a as jsonb), updated_at = :now"
                                        + " where profile_id = :id")
                        .param("a", mergedJson)
                        .param("now", ts(now))
                        .param("id", profileId)
                        .update();
            } catch (DataIntegrityViolationException e) {
                // 以 PostgreSQL 的 jsonb 文字長度為準的 ck_profile_attributes_size
                throw tooLarge();
            }
        }
    }

    private int insertBlank(String profileId, Instant now) {
        return jdbc.sql(
                        "insert into profile (profile_id, attributes, created_at, updated_at)"
                                + " values (:id, '{}'::jsonb, :now, :now) on conflict (profile_id) do nothing")
                .param("id", profileId)
                .param("now", ts(now))
                .update();
    }

    /** 回傳是否新增了這個識別碼；已屬於本客戶 = false；屬於別人 = 409。 */
    private boolean claim(String profileId, IdentifierKey key, Instant now) {
        int inserted =
                jdbc.sql(
                                "insert into profile_identifier (id_type, id_value, profile_id, created_at)"
                                        + " values (:type, :value, :id, :now)"
                                        + " on conflict (id_type, id_value) do nothing")
                        .param("type", key.type().name())
                        .param("value", key.value())
                        .param("id", profileId)
                        .param("now", ts(now))
                        .update();
        if (inserted > 0) {
            return true;
        }
        Optional<String> owner =
                jdbc.sql(
                                "select profile_id from profile_identifier"
                                        + " where id_type = :type and id_value = :value")
                        .param("type", key.type().name())
                        .param("value", key.value())
                        .query(String.class)
                        .optional();
        if (owner.isPresent() && !owner.get().equals(profileId)) {
            throw new ApiException(ErrorCode.IDENTIFIER_CONFLICT, "這個識別碼已屬於另一位客戶。");
        }
        return false;
    }

    private static Map<String, Object> merge(Map<String, Object> existing, Map<String, Object> patch) {
        Map<String, Object> out = new LinkedHashMap<>(existing);
        if (patch != null) {
            patch.forEach(
                    (k, v) -> {
                        if (v == null) {
                            out.remove(k);
                        } else {
                            out.put(k, v);
                        }
                    });
        }
        return out;
    }

    private static void validateAttributeKeys(Map<String, Object> patch, List<FieldError> errors) {
        if (patch == null) {
            return;
        }
        for (String key : patch.keySet()) {
            if (key == null || !ATTRIBUTE_KEY.matcher(key).matches()) {
                errors.add(new FieldError("attributes." + key, "鍵須符合 ^[a-z][a-z0-9_]*$"));
            }
        }
    }

    /** 依 (type, value) 排序、去重；固定的取鎖順序可降低死結。 */
    private TreeSet<IdentifierKey> normalizeIdentifiers(
            ProfileWriteRequest request, List<FieldError> errors) {
        TreeSet<IdentifierKey> out = new TreeSet<>();
        List<ProfileWriteRequest.IdentifierInput> inputs =
                request == null ? null : request.identifiers();
        if (inputs == null) {
            return out;
        }
        for (int i = 0; i < inputs.size(); i++) {
            ProfileWriteRequest.IdentifierInput in = inputs.get(i);
            String field = "identifiers[" + i + "]";
            IdentifierType type = in == null ? null : parseType(in.type());
            if (type == null) {
                errors.add(
                        new FieldError(
                                field + ".type", "須為 EMAIL、PHONE、MEMBER_ID、LINE_USER_ID、DEVICE_ID 之一"));
                continue;
            }
            Optional<String> value = normalizer.normalize(type, in.value());
            if (value.isEmpty()) {
                errors.add(new FieldError(field + ".value", "格式不正確"));
                continue;
            }
            out.add(new IdentifierKey(type, value.get()));
        }
        return out;
    }

    private static IdentifierType parseType(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return IdentifierType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ApiException tooLarge() {
        return invalid(List.of(new FieldError("attributes", "合併後超過 32 KB")));
    }

    private static ApiException invalid(List<FieldError> errors) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "請求內容不正確。", errors);
    }

    private static OffsetDateTime ts(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record IdentifierKey(IdentifierType type, String value)
            implements Comparable<IdentifierKey> {
        @Override
        public int compareTo(IdentifierKey o) {
            int c = type.compareTo(o.type);
            return c != 0 ? c : value.compareTo(o.value);
        }
    }
}
