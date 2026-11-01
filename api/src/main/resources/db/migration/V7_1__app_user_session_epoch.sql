-- V7_1：app_user.session_epoch（D-071）。管理員重設密碼時 +1，session 內存登入當下的值，不符即失效。
-- expand 型變更；down = ALTER TABLE app_user DROP COLUMN session_epoch。

ALTER TABLE app_user ADD COLUMN session_epoch INT NOT NULL DEFAULT 0;
