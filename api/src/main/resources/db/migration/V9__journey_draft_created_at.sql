-- V9：草稿建立時間（docs/spec/07-database-schema.md §12「V9」、docs/contracts/journeys.md §3「這份草稿的審核」）。
-- expand 型變更：只加可 null 的欄位，不回填、不加索引；舊版程式不讀此欄，可並存。
--
-- down（另開 migration 時使用）：
--   ALTER TABLE journey DROP COLUMN draft_created_at;

ALTER TABLE journey ADD COLUMN draft_created_at TIMESTAMPTZ;
