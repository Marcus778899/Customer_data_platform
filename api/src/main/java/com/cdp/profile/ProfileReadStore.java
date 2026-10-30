package com.cdp.profile;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 客戶資料的唯讀查詢（即時查表、不快取；寫入只經 {@link ProfileWriter}）。 */
public interface ProfileReadStore {

    Optional<ProfileRecord> find(String profileId);

    boolean exists(String profileId);

    List<ProfileRecord> findAll(Collection<String> profileIds);

    /** 各客戶的識別碼，依 type、value 排序。 */
    Map<String, List<StoredIdentifier>> identifiersOf(Collection<String> profileIds);

    /** 以（已正規化的）識別碼值找客戶 id；沒有 = 空。 */
    Optional<String> findByIdentifier(IdentifierType type, String value);
}
