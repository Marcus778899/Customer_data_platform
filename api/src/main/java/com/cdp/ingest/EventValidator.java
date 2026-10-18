package com.cdp.ingest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.cdp.common.web.FieldError;

/** 01 §3、§4、§6 的欄位規則（v0.1.0 子集見契約 §3.2）；回傳空清單表示通過。 */
@Component
public class EventValidator {

    private static final String NUL = String.valueOf((char) 0);
    private static final Pattern ULID = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);
    private static final Set<String> IDENTIFIER_TYPES =
            Set.of("EMAIL", "PHONE", "LINE_USER_ID", "MEMBER_ID", "DEVICE_ID");
    private static final Set<String> TRUST_LEVELS = Set.of("VERIFIED", "UNVERIFIED");

    public List<FieldError> validate(EventRequest event, Instant receivedAt) {
        List<FieldError> errors = new ArrayList<>();
        if (event == null) {
            errors.add(new FieldError("event", "事件不可為空"));
            return errors;
        }

        if (event.eventId() == null || !ULID.matcher(event.eventId()).matches()) {
            errors.add(new FieldError("event_id", "必填，且須為 ULID（26 字元）"));
        }

        if (event.sourceType() == null) {
            errors.add(new FieldError("source_type", "必填"));
        } else if (event.sourceType() != SourceType.CUSTOMER_BEHAVIOR && isBlank(event.sourceId())) {
            errors.add(new FieldError("source_id", event.sourceType() + " 必須提供 source_id"));
        }

        if (event.name() == null || !NAME.matcher(event.name()).matches()) {
            errors.add(new FieldError("name", "必填，且須符合 ^[a-z][a-z0-9_]*$"));
        }

        if (event.occurredAt() == null) {
            errors.add(new FieldError("occurred_at", "必填"));
        } else if (event.occurredAt().isAfter(receivedAt.plus(MAX_FUTURE_SKEW))) {
            errors.add(new FieldError("occurred_at", "不得超前伺服器時間 5 分鐘"));
        }

        validateIdentity(event.identity(), errors);
        validateContext(event, errors);
        validateNoNul(event, errors);
        return errors;
    }

    /** jsonb／text 不收 U+0000：所有字串（含 properties 的鍵與巢狀值）逐一檢查，errors 指出欄位路徑。 */
    private static void validateNoNul(EventRequest event, List<FieldError> errors) {
        checkNul("source_id", event.sourceId(), errors);
        EventIdentity identity = event.identity();
        if (identity != null) {
            checkNul("identity.profile_id", identity.profileId(), errors);
            if (identity.identifiers() != null) {
                for (int i = 0; i < identity.identifiers().size(); i++) {
                    Identifier id = identity.identifiers().get(i);
                    if (id != null) {
                        String base = "identity.identifiers[" + i + "]";
                        checkNul(base + ".type", id.type(), errors);
                        checkNul(base + ".value", id.value(), errors);
                    }
                }
            }
        }
        EventContext c = event.context();
        if (c != null) {
            checkNul("context.actor_id", c.actorId(), errors);
            checkNul("context.actor_role", c.actorRole(), errors);
            checkNul("context.trust_level", c.trustLevel(), errors);
            checkNul("context.ip", c.ip(), errors);
            checkNul("context.user_agent", c.userAgent(), errors);
            checkNul("context.request_id", c.requestId(), errors);
        }
        checkNulDeep("properties", event.properties(), errors);
    }

    private static void checkNulDeep(String path, Object value, List<FieldError> errors) {
        if (value instanceof String s) {
            checkNul(path, s, errors);
        } else if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String child = path + "." + key.replace(NUL, "<NUL>");
                if (key.contains(NUL)) {
                    errors.add(new FieldError(child, "鍵不可包含 U+0000"));
                }
                checkNulDeep(child, entry.getValue(), errors);
            }
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                checkNulDeep(path + "[" + i + "]", list.get(i), errors);
            }
        }
    }

    private static void checkNul(String field, String value, List<FieldError> errors) {
        if (value != null && value.contains(NUL)) {
            errors.add(new FieldError(field, "不可包含 U+0000"));
        }
    }

    private static void validateIdentity(EventIdentity identity, List<FieldError> errors) {
        if (identity == null || isBlank(identity.profileId())) {
            errors.add(new FieldError("identity.profile_id", "必填（v0.1.0 不做身分解析）"));
        }
        if (identity != null && identity.identifiers() != null) {
            List<Identifier> identifiers = identity.identifiers();
            for (int i = 0; i < identifiers.size(); i++) {
                Identifier id = identifiers.get(i);
                String base = "identity.identifiers[" + i + "]";
                if (id == null) {
                    errors.add(new FieldError(base, "不可為空"));
                    continue;
                }
                if (id.type() == null || !IDENTIFIER_TYPES.contains(id.type())) {
                    errors.add(new FieldError(base + ".type", "須為 " + IDENTIFIER_TYPES));
                }
                if (isBlank(id.value())) {
                    errors.add(new FieldError(base + ".value", "必填"));
                }
            }
        }
    }

    private static void validateContext(EventRequest event, List<FieldError> errors) {
        EventContext context = event.context();
        if (event.sourceType() == SourceType.INTERNAL_OPERATION
                && (context == null || isBlank(context.actorId()))) {
            errors.add(new FieldError("context.actor_id", "INTERNAL_OPERATION 必須提供 actor_id"));
        }
        if (event.sourceType() == SourceType.CUSTOMER_BEHAVIOR && context != null) {
            if (context.actorId() != null) {
                errors.add(new FieldError("context.actor_id", "CUSTOMER_BEHAVIOR 不得提供 actor_id"));
            }
            if (context.actorRole() != null) {
                errors.add(new FieldError("context.actor_role", "CUSTOMER_BEHAVIOR 不得提供 actor_role"));
            }
        }
        if (context != null
                && context.trustLevel() != null
                && !TRUST_LEVELS.contains(context.trustLevel())) {
            errors.add(new FieldError("context.trust_level", "須為 VERIFIED 或 UNVERIFIED"));
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
