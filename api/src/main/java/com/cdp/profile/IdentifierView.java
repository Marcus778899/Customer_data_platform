package com.cdp.profile;

/** profiles.md §1 {@code Identifier}（回應形狀；{@code masked} 僅回應有）。 */
public record IdentifierView(IdentifierType type, String value, boolean masked) {}
