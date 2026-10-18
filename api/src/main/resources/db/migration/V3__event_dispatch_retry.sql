-- V3：事件派發退避重試（D-024；語意見 docs/spec/07-database-schema.md §11）

-- 派發失敗次數；成功或放棄後不再變動
ALTER TABLE event ADD COLUMN dispatch_attempts INT NOT NULL DEFAULT 0;
-- 下次可派發時間；NULL = 未失敗過，立即可派發
ALTER TABLE event ADD COLUMN next_dispatch_at TIMESTAMPTZ;
-- 最近一次失敗的摘要（例外類別＋訊息，截斷）；不對外 API 輸出
ALTER TABLE event ADD COLUMN last_dispatch_error TEXT;
-- 派發結果；未派發時為 NULL
ALTER TABLE event ADD COLUMN dispatch_outcome TEXT;

ALTER TABLE event ADD CONSTRAINT ck_event_dispatch_outcome
    CHECK (dispatch_outcome IN ('DISPATCHED', 'LATE', 'EXHAUSTED'));
ALTER TABLE event ADD CONSTRAINT ck_event_dispatch_outcome_requires_dispatched
    CHECK (dispatch_outcome IS NULL OR dispatched_at IS NOT NULL);
ALTER TABLE event ADD CONSTRAINT ck_event_dispatch_attempts
    CHECK (dispatch_attempts >= 0);
ALTER TABLE event ADD CONSTRAINT ck_event_last_dispatch_error_size
    CHECK (char_length(last_dispatch_error) <= 1000);

-- 回填 V3 前已派發的列（遲到門檻 24h 同 01 §6）
UPDATE event
   SET dispatch_outcome = CASE
           WHEN received_at - occurred_at > INTERVAL '24 hours' THEN 'LATE'
           ELSE 'DISPATCHED'
       END
 WHERE dispatched_at IS NOT NULL;

-- 派發佇列：ORDER BY received_at, event_id 可直接走索引；退避中的列以 next_dispatch_at 過濾
DROP INDEX ix_event_undispatched;
CREATE INDEX ix_event_dispatch_queue
    ON event (received_at, event_id)
    WHERE dispatched_at IS NULL;
