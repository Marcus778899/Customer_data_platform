package com.cdp.delivery.relay;

import java.time.Clock;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.cdp.common.fault.FaultInjector;
import com.cdp.delivery.OutboxStore;

/**
 * 全系統唯一可依賴 RabbitTemplate 的類別（ArchitectureTest）。每列：發佈 body = idempotency_key → 等
 * publisher confirm → markPublished；confirm 失敗則留 PENDING 下輪再推。
 */
@Component
public class OutboxRelay {

    private final OutboxStore outbox;
    private final RabbitTemplate rabbit;
    private final RelayProperties properties;
    private final FaultInjector faults;
    private final Clock clock;

    public OutboxRelay(
            OutboxStore outbox,
            RabbitTemplate rabbit,
            RelayProperties properties,
            FaultInjector faults,
            Clock clock) {
        this.outbox = outbox;
        this.rabbit = rabbit;
        this.properties = properties;
        this.faults = faults;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${cdp.relay.poll-interval}")
    public void tick() {
        runOnce();
    }

    /** 推送一批，回傳筆數（測試可直接呼叫）。 */
    public int runOnce() {
        throw new UnsupportedOperationException("TODO(BE-02)");
    }
}
