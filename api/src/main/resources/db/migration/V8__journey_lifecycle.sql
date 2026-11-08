-- V8：旅程生命週期與審核（docs/spec/07-database-schema.md §12、docs/contracts/journeys.md §3；v0.3.0，D-055、D-056）。
-- expand 型變更：只加表與欄位，舊版程式照常讀寫 journey_definition；版本仍是不可變的 journey_definition 列。
-- 一個交易（Flyway 預設），失敗整支回滾。
--
-- down（另開 migration 時使用）：
--   DROP INDEX uq_enrollment_trigger;
--   ALTER TABLE enrollment_record DROP COLUMN trigger_event_id;
--   ALTER TABLE journey_definition DROP COLUMN layout;
--   DROP TABLE journey_review;
--   DROP TABLE journey;

CREATE TABLE journey (
    journey_id       TEXT PRIMARY KEY,
    status           TEXT NOT NULL,
    latest_version   INT,
    draft            JSONB,
    draft_layout     JSONB,
    draft_revision   INT NOT NULL DEFAULT 0,
    draft_updated_by TEXT,
    draft_updated_at TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,
    stopped_at       TIMESTAMPTZ,
    stop_reason      TEXT,
    stopped_by       TEXT,
    CONSTRAINT ck_journey_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ABORTED'))
);

CREATE INDEX ix_journey_list ON journey (updated_at DESC, journey_id DESC);

-- 回填：每個既有 journey_id 一列（ACTIVE、latest_version = 最大版本）。審核紀錄不回填。
INSERT INTO journey (journey_id, status, latest_version, created_at, updated_at)
SELECT journey_id, 'ACTIVE', max(version), min(created_at), max(created_at)
FROM journey_definition
GROUP BY journey_id;

CREATE TABLE journey_review (
    review_id    TEXT PRIMARY KEY,
    journey_id   TEXT NOT NULL,
    action       TEXT NOT NULL,
    status       TEXT NOT NULL,
    revision     INT,
    base_version INT,
    note         TEXT,
    submitted_by TEXT,
    submitted_at TIMESTAMPTZ NOT NULL,
    decided_by   TEXT,
    decided_at   TIMESTAMPTZ,
    reason       TEXT,
    version      INT,
    CONSTRAINT ck_journey_review_action CHECK (action IN ('PUBLISH')),
    CONSTRAINT ck_journey_review_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT ck_journey_review_reject_reason CHECK (status <> 'REJECTED' OR reason IS NOT NULL)
);

-- 一個旅程同時最多一筆送審中
CREATE UNIQUE INDEX uq_journey_review_pending ON journey_review (journey_id) WHERE status = 'PENDING';
CREATE INDEX ix_journey_review_journey ON journey_review (journey_id, submitted_at DESC, review_id DESC);

-- 畫布座標隨版本寫入（版本不可變）
ALTER TABLE journey_definition ADD COLUMN layout JSONB;

-- 重入（spec 02 §6「落地」）：同一事件對同一旅程只建一筆。回填只取每組第一筆，確保唯一索引一定建得起來。
ALTER TABLE enrollment_record ADD COLUMN trigger_event_id TEXT;

UPDATE enrollment_record e
SET trigger_event_id = t.event_id
FROM (
    SELECT enrollment_id,
           trigger_snapshot ->> 'event_id' AS event_id,
           row_number() OVER (
               PARTITION BY journey_id, trigger_snapshot ->> 'event_id'
               ORDER BY enrolled_at, enrollment_id) AS rn
    FROM enrollment_record
    WHERE trigger_snapshot ->> 'event_id' IS NOT NULL
) t
WHERE e.enrollment_id = t.enrollment_id AND t.rn = 1;

CREATE UNIQUE INDEX uq_enrollment_trigger
    ON enrollment_record (journey_id, trigger_event_id)
    WHERE trigger_event_id IS NOT NULL;
