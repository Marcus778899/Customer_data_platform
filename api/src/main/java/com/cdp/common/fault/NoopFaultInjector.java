package com.cdp.common.fault;

import org.springframework.stereotype.Component;

@Component
public class NoopFaultInjector implements FaultInjector {

    @Override
    public void at(FaultPoint point, String idempotencyKey) {
        // 正式環境不注入故障
    }
}
