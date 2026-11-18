-- V10：取消紀錄的原因（docs/spec/07-database-schema.md §12「V10」、docs/design/stop-cancel.md）。
-- expand 型變更：只加可 null 的欄位與約束，不回填、不加索引；舊版程式不寫此欄，可並存。
--
-- down（另開 migration 時使用）：
--   ALTER TABLE delivery_task DROP CONSTRAINT ck_delivery_task_cancel_reason;
--   ALTER TABLE delivery_task DROP COLUMN cancel_reason;

ALTER TABLE delivery_task ADD COLUMN cancel_reason TEXT;

ALTER TABLE delivery_task ADD CONSTRAINT ck_delivery_task_cancel_reason CHECK (
    cancel_reason IS NULL
    OR (status = 'CANCELLED' AND cancel_reason IN ('JOURNEY_STOPPED', 'ENROLLMENT_ENDED')));
