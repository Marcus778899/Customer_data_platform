-- V6：登入使用者與角色（docs/spec/07-database-schema.md §12、docs/contracts/auth.md §4）。只建 app_user，不動既有表。
-- email 一律存小寫（由應用程式正規化）；密碼只存 BCrypt 雜湊。

CREATE TABLE app_user (
    user_id       TEXT PRIMARY KEY,
    email         TEXT NOT NULL,
    display_name  TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    role          TEXT NOT NULL,
    status        TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_app_user_email UNIQUE (email),
    CONSTRAINT ck_app_user_role CHECK (role IN ('VIEWER', 'EDITOR', 'ADMIN')),
    CONSTRAINT ck_app_user_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);
