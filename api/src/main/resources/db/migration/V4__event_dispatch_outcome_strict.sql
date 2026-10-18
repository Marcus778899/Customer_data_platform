-- V4：dispatch_outcome 與 dispatched_at 雙向一致（語意見 docs/spec/07-database-schema.md §11「過渡」）
-- 已套用的 migration 不改內容（含註解，改了 Flyway checksum 會不符），V3 的反向步驟也寫在這裡。
-- 手動反向（Flyway Community 無 undo；每段單一交易，先 V4 後 V3）：
--   V4：ALTER TABLE event DROP CONSTRAINT ck_event_dispatch_outcome_matches_dispatched;
--       ALTER TABLE event ADD CONSTRAINT ck_event_dispatch_outcome_requires_dispatched
--           CHECK (dispatch_outcome IS NULL OR dispatched_at IS NOT NULL);
--       DELETE FROM flyway_schema_history WHERE version = '4';
--       （回填的 outcome 保留，不需還原）
--   V3：DROP INDEX ix_event_dispatch_queue;
--       CREATE INDEX ix_event_undispatched ON event (received_at) WHERE dispatched_at IS NULL;
--       ALTER TABLE event DROP CONSTRAINT ck_event_dispatch_outcome,
--           DROP CONSTRAINT ck_event_dispatch_outcome_requires_dispatched,
--           DROP CONSTRAINT ck_event_dispatch_attempts,
--           DROP CONSTRAINT ck_event_last_dispatch_error_size;
--       ALTER TABLE event DROP COLUMN dispatch_outcome, DROP COLUMN last_dispatch_error,
--           DROP COLUMN next_dispatch_at, DROP COLUMN dispatch_attempts;
--       DELETE FROM flyway_schema_history WHERE version = '3';
--       反向後程式須退回 FIX-03 之前；EXHAUSTED 列（已標 dispatched_at）不會再派發

-- 回填 V3～FIX-03a 之間派發、outcome 為 NULL 的列（規則同 V3；遲到門檻 24h 同 01 §6）
UPDATE event
   SET dispatch_outcome = CASE
           WHEN received_at - occurred_at > INTERVAL '24 hours' THEN 'LATE'
           ELSE 'DISPATCHED'
       END
 WHERE dispatched_at IS NOT NULL
   AND dispatch_outcome IS NULL;

-- 單向 CHECK 由雙向取代
ALTER TABLE event DROP CONSTRAINT ck_event_dispatch_outcome_requires_dispatched;
ALTER TABLE event ADD CONSTRAINT ck_event_dispatch_outcome_matches_dispatched
    CHECK ((dispatch_outcome IS NULL) = (dispatched_at IS NULL));
