-- V7：最小 ProfileStore 與客戶搜尋（docs/spec/07-database-schema.md §12、docs/contracts/profiles.md）。
-- 不變條件放 DB（DESIGN-03 做法 #5）：識別碼 PK、正規化 CHECK、屬性大小 CHECK；不論哪個入口寫入都擋得住髒資料。
-- 不動既有表，只加兩個瀏覽索引。

CREATE TABLE profile (
    profile_id TEXT PRIMARY KEY,
    attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_profile_attributes_size CHECK (octet_length(attributes::text) <= 32768)
);

CREATE TABLE profile_identifier (
    id_type    TEXT NOT NULL,
    id_value   TEXT NOT NULL,
    profile_id TEXT NOT NULL REFERENCES profile (profile_id),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_profile_identifier PRIMARY KEY (id_type, id_value),
    CONSTRAINT ck_profile_identifier_type
        CHECK (id_type IN ('EMAIL', 'PHONE', 'MEMBER_ID', 'LINE_USER_ID', 'DEVICE_ID')),
    CONSTRAINT ck_profile_identifier_norm CHECK (
        CASE id_type
            WHEN 'EMAIL' THEN id_value = lower(btrim(id_value)) AND id_value ~ '^[^@[:space:]]+@[^@[:space:]]+$'
            WHEN 'PHONE' THEN id_value ~ '^\+[1-9][0-9]{6,14}$'
            ELSE id_value = btrim(id_value) AND char_length(id_value) BETWEEN 1 AND 200
        END)
);

CREATE INDEX ix_profile_identifier_profile ON profile_identifier (profile_id);

-- 自既有 Enrollment 與事件回填空白客戶（不建識別碼、不帶屬性）
INSERT INTO profile (profile_id, attributes, created_at, updated_at)
SELECT profile_id, '{}'::jsonb, min(first_seen), min(first_seen)
FROM (
    SELECT profile_id, min(enrolled_at) AS first_seen
    FROM enrollment_record
    WHERE profile_id IS NOT NULL AND profile_id <> ''
    GROUP BY profile_id
    UNION ALL
    SELECT identity ->> 'profile_id', min(received_at)
    FROM event
    WHERE identity ->> 'profile_id' IS NOT NULL AND identity ->> 'profile_id' <> ''
    GROUP BY identity ->> 'profile_id'
) seen
GROUP BY profile_id
ON CONFLICT (profile_id) DO NOTHING;

-- /enrollments 省略 q 的瀏覽（journey-engine.md §4；節點統計也用第二個）
CREATE INDEX ix_enrollment_recent ON enrollment_record (enrolled_at DESC, enrollment_id DESC);
CREATE INDEX ix_enrollment_journey_recent ON enrollment_record (journey_id, enrolled_at DESC, enrollment_id DESC);
