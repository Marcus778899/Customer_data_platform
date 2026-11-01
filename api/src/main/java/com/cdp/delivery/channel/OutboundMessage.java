package com.cdp.delivery.channel;

import java.util.Map;

public record OutboundMessage(
        String idempotencyKey,
        String enrollmentId,
        String templateId,
        String channelId,
        Map<String, Object> variables) {}
