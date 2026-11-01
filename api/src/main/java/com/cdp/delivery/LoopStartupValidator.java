package com.cdp.delivery;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.cdp.delivery.relay.RelayProperties;

/** 啟動驗證（DESIGN-03「設定」）：lease 必須大於 confirm 逾時；排程執行緒池不得小於輪詢迴圈數。不符即拒絕啟動。 */
@Component
public class LoopStartupValidator implements InitializingBean {

    /** 輪詢迴圈數：EventDispatcher、AdvanceLoop、OutboxRelay。加迴圈時（v0.3.0 JourneyStopSweeper）一併調整。 */
    static final int LOOP_COUNT = 3;

    private final RelayProperties relay;
    private final Environment env;

    public LoopStartupValidator(RelayProperties relay, Environment env) {
        this.relay = relay;
        this.env = env;
    }

    @Override
    public void afterPropertiesSet() {
        if (relay.lease() == null
                || relay.confirmTimeout() == null
                || relay.lease().compareTo(relay.confirmTimeout()) <= 0) {
            throw new IllegalStateException(
                    "cdp.relay.lease (OUTBOX_RELAY_LEASE) must be greater than"
                            + " cdp.relay.confirm-timeout (OUTBOX_RELAY_CONFIRM_TIMEOUT): lease="
                            + relay.lease() + ", confirm-timeout=" + relay.confirmTimeout());
        }
        int pool = env.getProperty("spring.task.scheduling.pool.size", Integer.class, 1);
        if (pool < LOOP_COUNT) {
            throw new IllegalStateException(
                    "spring.task.scheduling.pool.size (CDP_SCHEDULER_POOL_SIZE) = " + pool
                            + " is smaller than the number of polling loops (" + LOOP_COUNT
                            + "); scheduler pool must be >= loop count");
        }
    }
}
