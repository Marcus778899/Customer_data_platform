-- V2：v0.1.0 推進與發送管線（語意見 docs/spec/07-database-schema.md §10）

-- 事件扇出：EventDispatcher 以 SKIP LOCKED 取未派發事件（A 類，Postgres 輪詢）
ALTER TABLE event ADD COLUMN dispatched_at TIMESTAMPTZ;

CREATE INDEX ix_event_undispatched
    ON event (received_at)
    WHERE dispatched_at IS NULL;

-- 軌跡查詢：依客戶列 Enrollment，新到舊（docs/contracts/journey-engine.md §3）
CREATE INDEX ix_enrollment_profile
    ON enrollment_record (tenant_id, profile_id, enrolled_at DESC, enrollment_id DESC);

-- Effect 交易外寄箱：與狀態同一交易寫入，relay 到期才推 RabbitMQ
CREATE TABLE effect_outbox(
    idempotency_key TEXT PRIMARY KEY,
    tenant_id       TEXT NOT NULL,
    enrollment_id   TEXT NOT NULL,
    journey_id      TEXT NOT NULL,
    journey_version INT  NOT NULL,
    node_id         TEXT NOT NULL,
    visit_seq       BIGINT NOT NULL,
    effect_index    INT  NOT NULL,
    effect_type     TEXT NOT NULL,
    payload         JSONB NOT NULL,
    status          TEXT NOT NULL,
    available_at    TIMESTAMPTZ NOT NULL,
    publish_count   INT  NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL,
    published_at    TIMESTAMPTZ,
    CONSTRAINT ck_effect_outbox_status
        CHECK (status IN ('PENDING', 'PUBLISHED', 'CANCELLED'))
);

CREATE INDEX ix_effect_outbox_due
    ON effect_outbox (available_at)
    WHERE status = 'PENDING';

CREATE INDEX ix_effect_outbox_enrollment
    ON effect_outbox (tenant_id, enrollment_id);

-- 任務級發送狀態（consumer 冪等的依據；也是 deliveryHistory 的完整紀錄）
CREATE TABLE delivery_task(
    idempotency_key TEXT PRIMARY KEY,
    tenant_id       TEXT NOT NULL,
    enrollment_id   TEXT NOT NULL,
    journey_id      TEXT NOT NULL,
    journey_version INT  NOT NULL,
    node_id         TEXT NOT NULL,
    visit_seq       BIGINT NOT NULL,
    template_id     TEXT NOT NULL,
    channel_id      TEXT NOT NULL,
    channel_type    TEXT NOT NULL,
    message_type    TEXT,
    status          TEXT NOT NULL,
    skip_reason     TEXT,
    delivery_id     TEXT,
    attempted_at    TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_delivery_task_status
        CHECK (status IN ('PENDING', 'SENT', 'SKIPPED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_delivery_task_skip_reason
        CHECK ((status = 'SKIPPED') = (skip_reason IS NOT NULL))
);

CREATE INDEX ix_delivery_task_enrollment
    ON delivery_task (tenant_id, enrollment_id, attempted_at DESC, idempotency_key DESC);

-- Mock 通道的「已送出」紀錄：驗收「恰好一則」數這張表
CREATE TABLE mock_channel_message(
    idempotency_key TEXT PRIMARY KEY,
    tenant_id       TEXT NOT NULL,
    enrollment_id   TEXT NOT NULL,
    template_id     TEXT NOT NULL,
    channel_id      TEXT NOT NULL,
    variables       JSONB NOT NULL,
    sent_at         TIMESTAMPTZ NOT NULL
);
