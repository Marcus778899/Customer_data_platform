package com.cdp.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 登入失敗限速（D-045、D-084，auth.md §3）。三層，全部在跑 BCrypt 之前檢查：
 * <ol>
 *   <li>全域併發：同時比對密碼的請求達上限（預設 CPU 核心數）→ 擋下，Retry-After 1，不計數。</li>
 *   <li>來源 IP：同一來源跨所有 email 的密碼錯誤數達 ip-max-failures → 該來源全部登入被鎖。</li>
 *   <li>email＋來源 IP：單一帳號被某來源猜錯達 max-failures → 只鎖那個來源對那個帳號。</li>
 * </ol>
 * 計數規則同第一版：密碼錯才 +1；距上次失敗超過 lockout 先歸零再計；鎖定中不計數、不延長；
 * 成功讓 email＋IP 的計數歸零（來源總量不歸零，免得有合法帳號的攻擊者洗掉計數）。
 * 計數存記憶體（重啟歸零），每張表有筆數上限，滿了淘汰最久未用的；所有操作 O(1)。
 */
@Component
public class LoginRateLimiter {

    /** 每張計數表的筆數上限。 */
    static final int MAX_ENTRIES = 100_000;

    private static final Duration BUSY_RETRY = Duration.ofSeconds(1);

    /** 一次嘗試的結果：成功歸零、密碼錯計數、其他（例如資料庫例外）只釋放名額不計數。 */
    public enum Outcome {
        SUCCESS,
        FAILURE,
        ABORTED
    }

    /** failures：目前鎖定週期內的密碼錯誤數；inFlight：已通過 tryBegin 但尚未 finish 的嘗試數。 */
    private record Entry(int failures, int inFlight, Instant lastFailure) {

        boolean live(Instant now, Duration lockout) {
            return lastFailure != null && lastFailure.plus(lockout).isAfter(now);
        }

        int liveFailures(Instant now, Duration lockout) {
            return live(now, lockout) ? failures : 0;
        }
    }

    /** 單一維度的計數表（LRU、有筆數上限；以 this 同步，臨界區都是 O(1)）。 */
    private static final class Table {
        private final int threshold;
        private final Duration lockout;
        private final boolean resetOnSuccess;
        private final Map<String, Entry> entries;

        Table(int threshold, Duration lockout, boolean resetOnSuccess, int capacity) {
            this.threshold = threshold;
            this.lockout = lockout;
            this.resetOnSuccess = resetOnSuccess;
            this.entries = new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                    return size() > capacity;
                }
            };
        }

        /** 回傳 null = 取得名額；否則是需等待的時間。 */
        synchronized Duration tryBegin(String key, Instant now) {
            Entry e = entries.get(key);
            int failures = e == null ? 0 : e.liveFailures(now, lockout);
            int inFlight = e == null ? 0 : e.inFlight();
            if (failures + inFlight >= threshold) {
                return failures >= threshold ? Duration.between(now, e.lastFailure().plus(lockout)) : BUSY_RETRY;
            }
            entries.put(key, new Entry(failures, inFlight + 1, e == null ? null : e.lastFailure()));
            return null;
        }

        synchronized void finish(String key, Outcome outcome, Instant now) {
            Entry e = entries.get(key);
            if (e == null) {
                return;
            }
            int inFlight = Math.max(0, e.inFlight() - 1);
            int failures = e.liveFailures(now, lockout);
            Instant last = e.lastFailure();
            switch (outcome) {
                case SUCCESS -> {
                    if (resetOnSuccess) {
                        failures = 0;
                        last = null;
                    }
                }
                case FAILURE -> {
                    if (failures < threshold) {
                        failures++;
                        last = now;
                    }
                }
                case ABORTED -> { }
            }
            if (failures == 0 && inFlight == 0) {
                entries.remove(key);
            } else {
                entries.put(key, new Entry(failures, inFlight, last));
            }
        }

        synchronized int size() {
            return entries.size();
        }
    }

    private final Clock clock;
    private final Semaphore concurrency;
    private final Table byAccount;
    private final Table bySource;

    @Autowired
    public LoginRateLimiter(LoginProperties properties, Clock clock) {
        this(properties, clock, Math.max(1, Runtime.getRuntime().availableProcessors()), MAX_ENTRIES);
    }

    LoginRateLimiter(LoginProperties properties, Clock clock, int maxConcurrent, int capacity) {
        if (properties.maxFailures() < 1) {
            throw new IllegalStateException("設定 cdp.login.max-failures（CDP_LOGIN_MAX_FAILURES）須 >= 1");
        }
        if (properties.ipMaxFailures() < 1) {
            throw new IllegalStateException("設定 cdp.login.ip-max-failures（CDP_LOGIN_IP_MAX_FAILURES）須 >= 1");
        }
        if (properties.lockout() == null || properties.lockout().isZero() || properties.lockout().isNegative()) {
            throw new IllegalStateException("設定 cdp.login.lockout（CDP_LOGIN_LOCKOUT）須為正的 Duration");
        }
        this.clock = clock;
        this.concurrency = new Semaphore(maxConcurrent);
        this.byAccount = new Table(properties.maxFailures(), properties.lockout(), true, capacity);
        this.bySource = new Table(properties.ipMaxFailures(), properties.lockout(), false, capacity);
    }

    /**
     * 原子地「檢查各層限制並佔用名額」。回傳空 = 取得名額，呼叫端必須在做完後呼叫 {@link #finish}；
     * 回傳等待時間 = 被擋下（不佔名額、不跑 BCrypt）。
     */
    public Optional<Duration> tryBegin(String email, String sourceIp) {
        Instant now = clock.instant();
        if (!concurrency.tryAcquire()) {
            return Optional.of(BUSY_RETRY);
        }
        Duration wait = bySource.tryBegin(sourceIp, now);
        if (wait == null) {
            wait = byAccount.tryBegin(accountKey(email, sourceIp), now);
            if (wait != null) {
                bySource.finish(sourceIp, Outcome.ABORTED, now);
            }
        }
        if (wait != null) {
            concurrency.release();
        }
        return Optional.ofNullable(wait);
    }

    /** 釋放 {@link #tryBegin} 取得的名額並依結果更新計數；被擋下的嘗試不會走到這裡。 */
    public void finish(String email, String sourceIp, Outcome outcome) {
        Instant now = clock.instant();
        try {
            byAccount.finish(accountKey(email, sourceIp), outcome, now);
            bySource.finish(sourceIp, outcome, now);
        } finally {
            concurrency.release();
        }
    }

    int accountEntries() {
        return byAccount.size();
    }

    int sourceEntries() {
        return bySource.size();
    }

    private static String accountKey(String email, String sourceIp) {
        return sourceIp + '|' + email;
    }

    /** Retry-After 秒數：整數、無條件進位、至少 1。 */
    public static long retryAfterSeconds(Duration remaining) {
        long nanos = remaining.toNanos();
        long seconds = (nanos + 999_999_999L) / 1_000_000_000L;
        return Math.max(1, seconds);
    }
}
