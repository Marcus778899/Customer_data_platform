-- V5：移除 tenant_id（D-063，REFACTOR-01；spec 07 §12「V5」）
-- 先明確 DROP INDEX / CONSTRAINT，再 DROP COLUMN，再以原名重建。

DROP INDEX uq_enrollment_dedup;
DROP INDEX ix_enrollment_wake_event;
DROP INDEX ix_enrollment_journey_active;
DROP INDEX ix_enrollment_profile;
DROP INDEX ix_event_name_time;
DROP INDEX ix_effect_outbox_enrollment;
DROP INDEX ix_delivery_task_enrollment;
ALTER TABLE event DROP CONSTRAINT event_pkey;

ALTER TABLE journey_definition    DROP COLUMN tenant_id;
ALTER TABLE enrollment_record     DROP COLUMN tenant_id;
ALTER TABLE node_trace            DROP COLUMN tenant_id;
ALTER TABLE event                 DROP COLUMN tenant_id;
ALTER TABLE effect_outbox         DROP COLUMN tenant_id;
ALTER TABLE delivery_task         DROP COLUMN tenant_id;
ALTER TABLE mock_channel_message  DROP COLUMN tenant_id;

ALTER TABLE event ADD CONSTRAINT event_pkey PRIMARY KEY (event_id);
CREATE INDEX ix_event_name_time ON event (name, occurred_at DESC);

CREATE UNIQUE INDEX uq_enrollment_dedup
    ON enrollment_record (journey_id, profile_id, dedup_key)
    WHERE dedup_key IS NOT NULL;
CREATE INDEX ix_enrollment_wake_event
    ON enrollment_record (profile_id, wake_event_name)
    WHERE status = 'WAITING' AND wake_event_name IS NOT NULL;
CREATE INDEX ix_enrollment_journey_active
    ON enrollment_record (journey_id)
    WHERE terminated_at IS NULL;
CREATE INDEX ix_enrollment_profile
    ON enrollment_record (profile_id, enrolled_at DESC, enrollment_id DESC);
CREATE INDEX ix_effect_outbox_enrollment ON effect_outbox (enrollment_id);
CREATE INDEX ix_delivery_task_enrollment
    ON delivery_task (enrollment_id, attempted_at DESC, idempotency_key DESC);
