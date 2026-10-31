-- D-077：到期佇列索引補第二排序欄（同名重建，查詢文字不變）。Flyway 一個交易，失敗整支回滾。
-- 不用 CONCURRENTLY（不能在交易內；啟動時這兩類列少）。V8 保留給 v0.3.0。
--
-- down（另開 migration 時使用）：同名重建回單欄，條件不變
--   DROP INDEX ix_enrollment_wake;
--   CREATE INDEX ix_enrollment_wake ON enrollment_record (next_wake_at)
--       WHERE status = 'WAITING' AND next_wake_at IS NOT NULL;
--   DROP INDEX ix_effect_outbox_due;
--   CREATE INDEX ix_effect_outbox_due ON effect_outbox (available_at)
--       WHERE status = 'PENDING';

DROP INDEX ix_enrollment_wake;
CREATE INDEX ix_enrollment_wake
    ON enrollment_record (next_wake_at, enrollment_id)
    WHERE status = 'WAITING' AND next_wake_at IS NOT NULL;

DROP INDEX ix_effect_outbox_due;
CREATE INDEX ix_effect_outbox_due
    ON effect_outbox (available_at, idempotency_key)
    WHERE status = 'PENDING';
