package com.cdp.delivery.channel;

/** 通道（05 §7 的 v0.1.0 子集）。同一 idempotencyKey 送兩次只送一次。 */
public interface ChannelAdapter {

    /** 寫進 delivery_task.channel_type，例：MOCK。 */
    String channelType();

    SendResult send(OutboundMessage message);
}
