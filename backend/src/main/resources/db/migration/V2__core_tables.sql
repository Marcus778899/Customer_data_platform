CREATE TABLE journey_definition(
    journey_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    version INT NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    status TEXT NOT NULL,
    settings JSONB NOT NULL,
    entry_node TEXT NOT NULL,
    nodes JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    PRIMARY KEY (journey_id, version)
);

--
CREATE TABLE enrollment_record (
    enrollment_id      TEXT PRIMARY KEY,
    tenant_id          TEXT NOT NULL,
    journey_id         TEXT NOT NULL,
    journey_version    INT  NOT NULL,
    profile_id         TEXT NOT NULL,
    status             TEXT NOT NULL,
    current_node_id    TEXT NOT NULL,

    wake_type          TEXT,
    next_wake_at       TIMESTAMPTZ,
    wake_event_name    TEXT,
    wake_event_filter  JSONB,

    trigger_snapshot   JSONB NOT NULL,
    dedup_key          TEXT,
    variables          JSONB NOT NULL DEFAULT '{}',
    delivery_history   JSONB NOT NULL DEFAULT '[]',

    enrolled_at        TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,
    terminated_at      TIMESTAMPTZ,
    termination_reason TEXT
);

CREATE UNIQUE INDEX uq_enrollment_dedup
    ON enrollment_record (tenant_id, journey_id, profile_id, dedup_key)
    WHERE dedup_key IS NOT NULL;

CREATE INDEX ix_enrollment_wake
    ON enrollment_record (next_wake_at)
    WHERE status = 'WAITING' AND next_wake_at IS NOT NULL;

CREATE INDEX ix_enrollment_wake_event
    ON enrollment_record (tenant_id, profile_id, wake_event_name)
    WHERE status = 'WAITING' AND wake_event_name IS NOT NULL;

CREATE INDEX ix_enrollment_journey_active
    ON enrollment_record (tenant_id, journey_id)
    WHERE terminated_at IS NULL;

--
CREATE TABLE node_trace(
    enrollment_id TEXT NOT NULL,
    seq BIGINT NOT NULL,
    tenant_id TEXT NOT NULL,
    node_id TEXT NOT NULL,
    node_type TEXT NOT NULL,
    entered_at TIMESTAMPTZ NOT NULL,
    exited_at TIMESTAMPTZ,
    out_port TEXT,
    decision_reason TEXT NOT NULL,
    evaluated JSONB NOT NULL,
    PRIMARY KEY (enrollment_id, seq)
);

--
CREATE TABLE event(
    event_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    source_type TEXT NOT NULL,
    source_id TEXT,
    name TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    identity JSONB NOT NULL,
    properties JSONB,
    context JSONB,
    PRIMARY KEY (tenant_id, event_id)
);

CREATE INDEX ix_event_name_time
    ON event (tenant_id, name, occurred_at DESC);
