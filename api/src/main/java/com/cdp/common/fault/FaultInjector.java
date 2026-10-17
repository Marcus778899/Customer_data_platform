package com.cdp.common.fault;

/** 正式環境為 no-op；測試以 @Primary bean 覆蓋並在指定點丟例外。 */
public interface FaultInjector {

    void at(FaultPoint point, String idempotencyKey);
}
