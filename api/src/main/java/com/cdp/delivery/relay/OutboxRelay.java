package com.cdp.delivery.relay;

import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.cdp.common.fault.FaultInjector;
import com.cdp.common.fault.FaultPoint;
import com.cdp.delivery.LoopProperties;
import com.cdp.delivery.OutboxEntry;
import com.cdp.delivery.OutboxStore;
import com.cdp.delivery.OutboxStore.Lease;
import com.cdp.delivery.RabbitTopology;

/**
 * 全系統唯一可依賴 RabbitTemplate 的類別（ArchitectureTest）。spec 07 §12「relay」：搶列（短交易，順便設 lease）→
 * 交易外整批 convertAndSend（CorrelationData）→ 一起等 confirm（上限 confirmTimeout）→ 一個小交易 batchUpdate 條件式回寫。
 * 回寫以 {@code available_at = lease} 為 fencing：lease 已被別人接手或列已取消，回寫影響 0 列，結果丟棄。
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxStore outbox;
    private final RabbitTemplate rabbit;
    private final RelayProperties properties;
    private final LoopProperties loop;
    private final FaultInjector faults;
    private final Clock clock;
    private final TransactionTemplate tx;

    public OutboxRelay(
            OutboxStore outbox,
            RabbitTemplate rabbit,
            RelayProperties properties,
            LoopProperties loop,
            FaultInjector faults,
            Clock clock,
            TransactionTemplate tx) {
        this.outbox = outbox;
        this.rabbit = rabbit;
        this.properties = properties;
        this.loop = loop;
        this.faults = faults;
        this.clock = clock;
        this.tx = tx;
    }

    /** 一輪的結果：claimed = 搶到幾列，published = 其中成功標 PUBLISHED 的筆數，clean = 全數成功。 */
    private record Round(int claimed, int published, boolean clean) {}

    private record Pending(Lease lease, CorrelationData correlation) {}

    /** 滿批續做，直到不滿批、本輪有失敗（broker 異常時不空轉），或超過 CDP_LOOP_MAX_DRAIN。 */
    @Scheduled(fixedDelayString = "${cdp.relay.poll-interval}")
    public void tick() {
        long deadline = System.nanoTime() + loop.maxDrain().toNanos();
        Round round;
        do {
            round = round();
        } while (round.claimed() >= properties.batchSize()
                && round.clean()
                && System.nanoTime() < deadline);
    }

    /** 推送一批，回傳標為 PUBLISHED 的筆數（測試可直接呼叫）。 */
    public int runOnce() {
        return round().published();
    }

    private Round round() {
        Instant now = clock.instant();
        // Postgres timestamptz 只到微秒；先截斷，fencing 比對才不會差奈秒
        Instant leaseUntil = now.plus(properties.lease()).truncatedTo(ChronoUnit.MICROS);
        List<OutboxEntry> claimed =
                tx.execute(status -> outbox.claimDue(now, leaseUntil, properties.batchSize()));
        if (claimed == null || claimed.isEmpty()) {
            return new Round(0, 0, true);
        }

        List<Lease> failed = new ArrayList<>();
        List<Lease> succeeded = new ArrayList<>();
        try {
            // 整批在同一條 channel 上送出並等 confirm（不在 DB 交易內）：每則各取 channel 時，帶著未 confirm 訊息的
            // channel 不會回 cache，broker 無回應時下一則要開新 channel 而卡死
            rabbit.invoke(
                    ops -> {
                        sendAndConfirm(ops, claimed, failed, succeeded);
                        return null;
                    });
        } catch (RuntimeException e) {
            log.warn("relay channel failure; unresolved rows keep their lease: {}", e.toString());
        }

        int published = writeBack(succeeded, failed);
        boolean clean = failed.isEmpty() && published == claimed.size();
        return new Round(claimed.size(), published, clean);
    }

    private void sendAndConfirm(
            RabbitOperations ops, List<OutboxEntry> claimed, List<Lease> failed, List<Lease> succeeded) {
        List<Pending> inFlight = new ArrayList<>();
        boolean sendBroken = false;
        for (OutboxEntry entry : claimed) {
            String key = entry.idempotencyKey();
            Lease lease = new Lease(key, entry.availableAt());
            String routingKey = routingKey(entry.effectType());
            if (routingKey == null) {
                log.error("unknown effect_type {} for outbox {}; rescheduled", entry.effectType(), key);
                failed.add(lease);
                continue;
            }
            if (sendBroken) {
                failed.add(lease); // broker 不可用時不逐列等逾時
                continue;
            }
            try {
                faults.at(FaultPoint.RELAY_BEFORE_PUBLISH, key);
            } catch (RuntimeException e) {
                // 模擬在此中斷：不發佈也不回寫，列維持被 lease 住，到期後由下一輪重搶
                log.warn("relay interrupted before publish for {}: {}", key, e.toString());
                continue;
            }
            CorrelationData correlation = new CorrelationData(key);
            try {
                ops.convertAndSend(RabbitTopology.EXCHANGE, routingKey, key, correlation);
                inFlight.add(new Pending(lease, correlation));
            } catch (RuntimeException e) {
                log.warn("publish failed for outbox {}; rescheduled: {}", key, e.toString());
                failed.add(lease);
                sendBroken = true;
            }
        }

        long deadline = System.nanoTime() + properties.confirmTimeout().toNanos();
        for (Pending p : inFlight) {
            String key = p.lease().idempotencyKey();
            String failure = awaitConfirm(p.correlation(), deadline);
            if (failure != null) {
                log.warn("publish failed for outbox {}; rescheduled: {}", key, failure);
                failed.add(p.lease());
                continue;
            }
            try {
                faults.at(FaultPoint.RELAY_AFTER_CONFIRM, key);
                faults.at(FaultPoint.RELAY_BEFORE_WRITEBACK, key);
            } catch (RuntimeException e) {
                log.warn("relay interrupted before writeback for {}: {}", key, e.toString());
                continue;
            }
            succeeded.add(p.lease());
        }

    }

    /** 一個小交易、batchUpdate 條件式回寫；回傳實際標為 PUBLISHED 的筆數。 */
    private int writeBack(List<Lease> succeeded, List<Lease> failed) {
        if (succeeded.isEmpty() && failed.isEmpty()) {
            return 0;
        }
        Instant at = clock.instant();
        int[][] counts = new int[1][];
        tx.executeWithoutResult(
                status -> {
                    counts[0] = outbox.markPublishedAll(succeeded, at);
                    outbox.rescheduleAll(failed, at.plus(properties.retryDelay()));
                });
        int published = 0;
        for (int i = 0; i < succeeded.size(); i++) {
            if (counts[0][i] > 0 || counts[0][i] == Statement.SUCCESS_NO_INFO) {
                published++;
            } else {
                log.info("lease lost for outbox {}; late confirm discarded", succeeded.get(i).idempotencyKey());
            }
        }
        return published;
    }

    /** 回傳 null 表示 broker 已 ack 且未被 returned，否則為失敗原因；所有列共用同一個截止時間。 */
    private static String awaitConfirm(CorrelationData correlation, long deadlineNanos) {
        try {
            long remaining = Math.max(0, deadlineNanos - System.nanoTime());
            CorrelationData.Confirm confirm =
                    correlation.getFuture().get(remaining, TimeUnit.NANOSECONDS);
            if (!confirm.isAck()) {
                return "nack: " + confirm.getReason();
            }
            if (correlation.getReturned() != null) {
                return "returned: " + correlation.getReturned().getReplyText();
            }
            return null;
        } catch (TimeoutException e) {
            return "confirm timeout";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return e.toString();
        } catch (ExecutionException | RuntimeException e) {
            return e.toString();
        }
    }

    private static String routingKey(String effectType) {
        return "SendMessage".equals(effectType) ? RabbitTopology.SEND_MESSAGE_ROUTING_KEY : null;
    }
}
