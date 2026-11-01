package com.cdp.profile;

/** 讀自 {@code profile_identifier} 的一列（值已正規化、未遮罩）。 */
public record StoredIdentifier(String profileId, IdentifierType type, String value) {}
