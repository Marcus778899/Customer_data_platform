package com.cdp.delivery;

/** 取消點檢查（05 §1、§5）。v0.1.0 只看 journey_definition.status，Redis 於 v0.3.0。 */
public interface KillSwitch {

    boolean isStopped(String journeyId, int journeyVersion);
}
