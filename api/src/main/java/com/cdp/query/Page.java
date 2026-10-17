package com.cdp.query;

import java.util.List;

/** 契約 §4：next_cursor 為 null 表示沒有下一頁。 */
public record Page<T>(List<T> items, String nextCursor) {}
