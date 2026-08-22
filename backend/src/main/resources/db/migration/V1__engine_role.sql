-- V1：引擎專用 role（RLS 的唯一例外）
--
-- 問題：推進迴圈的 StateStore.lockDue(now, limit) 必須跨租戶掃「所有到期的 Enrollment」，
--       但 docs/spec/README.md 的共通約定要求所有表開啟 RLS、以 session 變數 app.tenant_id 為準。
--       RLS 開著時該查詢回傳空集合，計時器一則都推不動。
--
-- 解法：一個帶 BYPASSRLS 的 NOLOGIN role，只給推進迴圈與 outbox relay 使用。
--       不另開登入帳號——應用程式仍用同一組憑證連線，由引擎的連線池在取得連線時
--       執行 SET ROLE journey_engine 切換過去。少一組密碼要保管，也少一條外洩路徑。
--
-- 🚫 鐵則：這個 role 的連線池不得被任何其他元件共用。
--          API 層、報表層、客服查詢一律走受 RLS 保護的預設 role。
--          共用一次，RLS 就形同虛設——而 RLS 存在的目的正是「讓遺漏成為不可能，而非靠自律」。

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'journey_engine') THEN
        -- NOLOGIN：不能直接連線，只能被 SET ROLE 切換
        -- BYPASSRLS：略過所有 RLS policy，這是它存在的唯一理由
        CREATE ROLE journey_engine NOLOGIN BYPASSRLS;
    END IF;
END
$$;

-- 讓目前的應用程式帳號可以 SET ROLE journey_engine
DO $$
BEGIN
    EXECUTE format('GRANT journey_engine TO %I', current_user);
END
$$;

COMMENT ON ROLE journey_engine IS
    '引擎專用 role：僅供旅程推進迴圈與 outbox relay 使用，帶 BYPASSRLS。連線池不得與其他元件共用。';

-- 註：BYPASSRLS 屬性需要 superuser 才能授予。
--     本機開發環境的 POSTGRES_USER 由 postgres 映像建立為 superuser，因此本檔可直接執行；
--     正式環境若應用程式帳號非 superuser，這段須改由基礎設施佈建（Terraform / DBA），
--     並在 Flyway 中以 baseline 略過。
