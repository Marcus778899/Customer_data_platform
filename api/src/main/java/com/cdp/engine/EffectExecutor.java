package com.cdp.engine;

import com.cdp.engine.node.Effect;

/** Effect 執行（04 §7）。v0.1.0 由 RabbitMQ consumer 呼叫，只支援 SendMessage → Mock 通道。 */
public interface EffectExecutor {

    void execute(String enrollmentId, Effect effect);

    void cancelPending(String enrollmentId);

    void cancelPendingByJourney(String journeyId);
}
