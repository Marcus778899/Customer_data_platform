package com.cdp.profile;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.cdp.common.web.ApiException;
import com.cdp.common.web.ErrorCode;
import com.cdp.query.PiiMasker;

/** profiles.md §2、§3：寫入經 {@link ProfileWriter}，讀取即時查表並依白名單遮罩。 */
@Service
public class ProfileService {

    private final ProfileWriter writer;
    private final ProfileReadStore store;
    private final PiiMasker masker;
    private final Clock clock;

    public ProfileService(
            ProfileWriter writer, ProfileReadStore store, PiiMasker masker, Clock clock) {
        this.writer = writer;
        this.store = store;
        this.masker = masker;
        this.clock = clock;
    }

    public ProfileDetail put(String profileId, ProfileWriteRequest request) {
        writer.upsert(profileId, request);
        return detail(profileId);
    }

    public ProfileDetail detail(String profileId) {
        ProfileRecord record =
                store.find(profileId)
                        .orElseThrow(
                                () -> new ApiException(ErrorCode.PROFILE_NOT_FOUND, "找不到這位客戶。"));
        List<StoredIdentifier> ids =
                store.identifiersOf(List.of(profileId)).getOrDefault(profileId, List.of());
        List<String> paths = new ArrayList<>();
        Map<String, Object> attributes = masker.maskAttributes(record.attributes(), paths);
        List<IdentifierView> views = maskIdentifiers(ids, paths);
        return new ProfileDetail(
                profileId,
                views,
                record.createdAt(),
                record.updatedAt(),
                attributes,
                paths,
                clock.instant());
    }

    public void requireExists(String profileId) {
        if (!store.exists(profileId)) {
            throw new ApiException(ErrorCode.PROFILE_NOT_FOUND, "找不到這位客戶。");
        }
    }

    List<IdentifierView> maskIdentifiers(List<StoredIdentifier> ids, List<String> paths) {
        List<IdentifierView> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            StoredIdentifier id = ids.get(i);
            String masked =
                    masker.maskIdentifier(
                            id.type().name(), id.value(), "identifiers[" + i + "].value", paths);
            out.add(
                    masked == null
                            ? new IdentifierView(id.type(), id.value(), false)
                            : new IdentifierView(id.type(), masked, true));
        }
        return out;
    }
}
