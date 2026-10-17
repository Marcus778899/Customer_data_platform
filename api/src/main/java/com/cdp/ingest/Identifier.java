package com.cdp.ingest;

/** type：EMAIL／PHONE／LINE_USER_ID／MEMBER_ID／DEVICE_ID（01 §3）。 */
public record Identifier(String type, String value) {}
