package com.cdp.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 登入失敗限速（D-045，auth.md §3）。維度 = 小寫 email（帳號存在與否都一樣計數）；密碼錯才 +1；
 * 距上次失敗超過 lockout 先歸零再計；計數達 maxFailures → 鎖到「最後一次失敗 + lockout」，鎖定中的嘗試不計數、不延長；
 * 成功歸零。計數存記憶體，後端重啟歸零。
 */
@Component
public class LoginRateLimiter {

    /** 超過這個筆數時順手清掉已過期的項目，避免亂填 email 撐爆記憶體。 */
    private static final int PURGE_THRESHOLD = 10_000;

    private record Entry(int failures, Instant lastFailure) {}

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

    /** 鎖定中 → 剩餘時間；沒鎖 → {@link Duration#ZERO}。 */
    public Duration remainingLock(String key) {
        Entry e = entries.get(key);
        if (e == null || e.failures() < maxFailures) {
            return Duration.ZERO;
        }
        Duration remaining = Duration.between(clock.instant(), e.lastFailure().plus(lockout));
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    /** 記一次密碼錯誤。鎖定中的嘗試呼叫端不會呼叫這個方法；這裡再保險一次，不延長鎖定。 */
    public void recordFailure(String key) {
        Instant now = clock.instant();
        if (entries.size() > PURGE_THRESHOLD) {
            entries.entrySet().removeIf(en -> !en.getValue().lastFailure().plus(lockout).isAfter(now));
        }
        entries.compute(key, (k, e) -> {
            boolean live = e != null && e.lastFailure().plus(lockout).isAfter(now);
            if (live && e.failures() >= maxFailures) {
                return e;
            }
            return new Entry(live ? e.failures() + 1 : 1, now);
        });
    }

    public void reset(String key) {
        entries.remove(key);
    }

    /** Retry-After 秒數：整數、無條件進位、至少 1。 */
    public static long retryAfterSeconds(Duration remaining) {
        long nanos = remaining.toNanos();
        long seconds = (nanos + 999_999_999L) / 1_000_000_000L;
        return Math.max(1, seconds);
    }
}
