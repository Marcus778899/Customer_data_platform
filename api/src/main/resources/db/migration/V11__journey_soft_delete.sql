-- V11：刪除未發佈的旅程（docs/spec/07-database-schema.md §12「V11」、docs/contracts/journeys.md §3.6）。
-- expand 型變更：只加可 null 的欄位與約束，不回填、不加索引；舊版程式不讀這兩欄，可並存
-- （退版後已刪除的旅程會重新出現，資料不失）。
--
-- down（另開 migration 時使用）：
--   ALTER TABLE journey DROP CONSTRAINT ck_journey_deleted;
--   ALTER TABLE journey DROP COLUMN deleted_by;
--   ALTER TABLE journey DROP COLUMN deleted_at;

ALTER TABLE journey ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE journey ADD COLUMN deleted_by TEXT;
ALTER TABLE journey ADD CONSTRAINT ck_journey_deleted CHECK (
    (deleted_at IS NULL AND deleted_by IS NULL)
    OR (deleted_at IS NOT NULL AND deleted_by IS NOT NULL AND status = 'DRAFT'));
