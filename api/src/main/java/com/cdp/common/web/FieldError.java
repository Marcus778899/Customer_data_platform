package com.cdp.common.web;

/** 逐欄錯誤；field 為 JSON 路徑（例：identity.profile_id、events[3].occurred_at）。 */
public record FieldError(String field, String message) {}
