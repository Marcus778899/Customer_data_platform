package com.cdp.profile;

import java.util.List;
import java.util.Map;

/** profiles.md §2 Request。{@code attributes} 的 null 值 = 刪除該鍵，所以用 Map 保留 null。 */
public record ProfileWriteRequest(Map<String, Object> attributes, List<IdentifierInput> identifiers) {

    /** {@code type} 保留字串，由服務層驗證並回報 {@code identifiers[i].type}。 */
    public record IdentifierInput(String type, String value) {}
}
