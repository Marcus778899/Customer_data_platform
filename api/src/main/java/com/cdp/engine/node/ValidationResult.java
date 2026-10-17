package com.cdp.engine.node;

import java.util.List;

/** 發布前驗證結果；errors 為空表示通過。 */
public record ValidationResult(List<String> errors) {

    public static final ValidationResult OK = new ValidationResult(List.of());
}
