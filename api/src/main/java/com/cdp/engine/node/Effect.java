package com.cdp.engine.node;

import java.util.Map;

/**
 * 節點回傳的副作用（04 §5）。節點一律填 idempotencyKey = null，由引擎蓋章。 v0.1.0 只有 SendMessage；其他型別在用到的那期加入。
 */
public sealed interface Effect {

    String idempotencyKey();

    Effect withIdempotencyKey(String idempotencyKey);

    record SendMessage(
            String idempotencyKey,
            String templateId,
            String channelId,
            Map<String, Object> variables)
            implements Effect {

        @Override
        public SendMessage withIdempotencyKey(String idempotencyKey) {
            return new SendMessage(idempotencyKey, templateId, channelId, variables);
        }
    }
}
