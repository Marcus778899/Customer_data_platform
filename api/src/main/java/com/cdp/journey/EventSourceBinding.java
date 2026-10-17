package com.cdp.journey;

/** 某事件名稱命中的 ACTIVE 旅程版本與其 EventSourceNode。 */
public record EventSourceBinding(String journeyId, int version, String sourceNodeId) {}
