package com.cdp.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 登入失敗限速（D-045，auth.md §3）。維度 = 小寫 email（帳號存在與否都一樣計數）；密碼錯才 +1；
 * 距上次失敗超過 lockout 先歸零再計；計數達 maxFailures → 鎖到「最後一次失敗 + lockout」，鎖定中的嘗試不計數、不延長；
 * 成功歸零。計數存記憶體，後端重啟歸零。
 */
@Component
public class LoginRateLimiter {

    /** 超過這個筆數時順手清掉已過期的項目，避免亂填 email 撐爆記憶體（email 長度另有上限）。 */
    private static final int PURGE_THRESHOLD = 10_000;

    /** 一次嘗試的結果：成功歸零、密碼錯計數、其他（例如資料庫例外）只釋放名額不計數。 */
    public enum Outcome {
        SUCCESS,
        FAILURE,
        ABORTED
    }

    /** failures：目前鎖定週期內的密碼錯誤數；inFlight：已通過 {@link #tryBegin} 但尚未 {@link #finish} 的嘗試數。 */
    private record Entry(int failures, int inFlight, Instant lastFailure) {

        boolean live(Instant now, Duration lockout) {
            return lastFailure != null && lastFailure.plus(lockout).isAfter(now);
        }

        int liveFailures(Instant now, Duration lockout) {
            return live(now, lockout) ? failures : 0;
        }
    }

    private final int maxFailures;
    private final Duration lockout;
    private final Clock clock;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public LoginRateLimiter(LoginProperties properties, Clock clock) {
        if (properties.maxFailures() < 1) {
            throw new IllegalStateException("設定 cdp.login.max-failures（CDP_LOGIN_MAX_FAILURES）須 >= 1");
        }
        if (properties.lockout() == null || properties.lockout().isZero() || properties.lockout().isNegative()) {
            throw new IllegalStateException("設定 cdp.login.lockout（CDP_LOGIN_LOCKOUT）須為正的 Duration");
        }
        this.maxFailures = properties.maxFailures();
        this.lockout = properties.lockout();
        this.clock = clock;
    }

    /**
     * 原子地「檢查鎖定並佔用一次嘗試名額」。回傳空 = 取得名額，呼叫端必須在做完後呼叫 {@link #finish}；
     * 回傳鎖定剩餘時間 = 被擋下（不佔名額、不跑 BCrypt）。「已記錄的失敗 + 進行中的嘗試」達門檻即擋，
     * 所以並行請求最多只有門檻數個能跑 BCrypt。
     */
    public Optional<Duration> tryBegin(String key) {
        Instant now = clock.instant();
        if (entries.size() > PURGE_THRESHOLD) {
            entries.entrySet().removeIf(en -> en.getValue().inFlight() == 0 && !en.getValue().live(now, lockout));
        }
        Duration[] blocked = new Duration[1];
        entries.compute(key, (k, e) -> {
            int failures = e == null ? 0 : e.liveFailures(now, lockout);
            int inFlight = e == null ? 0 : e.inFlight();
            if (failures + inFlight >= maxFailures) {
                // 已鎖定 → 剩餘到 最後一次失敗 + lockout；只是名額被進行中的嘗試佔滿 → 先請對方等 1 秒
                blocked[0] = failures >= maxFailures
                        ? Duration.between(now, e.lastFailure().plus(lockout))
                        : Duration.ofSeconds(1);
                return e;
            }
            return new Entry(failures, inFlight + 1, e == null ? null : e.lastFailure());
        });
        return Optional.ofNullable(blocked[0]);
    }

    /** 釋放 {@link #tryBegin} 取得的名額並依結果更新計數；鎖定中的嘗試不會走到這裡。 */
    public void finish(String key, Outcome outcome) {
        Instant now = clock.instant();
        entries.compute(key, (k, e) -> {
            if (e == null) {
                return null;
            }
            int inFlight = Math.max(0, e.inFlight() - 1);
            int failures = e.liveFailures(now, lockout);
            Instant last = e.lastFailure();
            switch (outcome) {
                case SUCCESS -> {
                    failures = 0;
                    last = null;
                }
                case FAILURE -> {
                    // 距上次失敗超過 lockout 已在 liveFailures 歸零；鎖定中不延長
                    if (failures < maxFailures) {
                        failures++;
                        last = now;
                    }
                }
                case ABORTED -> { }
            }
            return failures == 0 && inFlight == 0 ? null : new Entry(failures, inFlight, last);
        });
    }

    /** Retry-After 秒數：整數、無條件進位、至少 1。 */
    public static long retryAfterSeconds(Duration remaining) {
        long nanos = remaining.toNanos();
        long seconds = (nanos + 999_999_999L) / 1_000_000_000L;
        return Math.max(1, seconds);
    }
}
