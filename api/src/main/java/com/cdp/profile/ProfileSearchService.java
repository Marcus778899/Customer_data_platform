package com.cdp.profile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.cdp.common.web.ApiException;
import com.cdp.common.web.ErrorCode;
import com.cdp.common.web.FieldError;

/** profiles.md §3 {@code q} 的四種比對（精確、聯集、同一客戶只列一次，matched_by 取最先命中者）。 */
@Service
public class ProfileSearchService {

    private static final int MAX_Q = 200;
    private static final int MAX_ITEMS = 20;

    private final ProfileReadStore store;
    private final IdentifierNormalizer normalizer;
    private final ProfileService profiles;

    public ProfileSearchService(
            ProfileReadStore store, IdentifierNormalizer normalizer, ProfileService profiles) {
        this.store = store;
        this.normalizer = normalizer;
        this.profiles = profiles;
    }

    public ProfileSearchResponse search(String q) {
        if (q == null
                || q.isBlank()
                || q.codePointCount(0, q.length()) > MAX_Q
                || q.indexOf((char) 0) >= 0) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "請求參數不正確。",
                    List.of(new FieldError("q", "必填，1 到 200 字")));
        }
        String trimmed = q.strip();
        Map<String, MatchedBy> hits = new LinkedHashMap<>();

        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (ProfileWriter.ULID.matcher(upper).matches() && store.exists(upper)) {
            hits.putIfAbsent(upper, MatchedBy.PROFILE_ID);
        }
        if (trimmed.indexOf('@') >= 0) {
            lookup(IdentifierType.EMAIL, trimmed, hits);
        }
        lookup(IdentifierType.PHONE, trimmed, hits);
        lookup(IdentifierType.MEMBER_ID, trimmed, hits);
        lookup(IdentifierType.LINE_USER_ID, trimmed, hits);
        lookup(IdentifierType.DEVICE_ID, trimmed, hits);
        if (hits.isEmpty()) {
            return new ProfileSearchResponse(List.of());
        }

        Map<String, ProfileRecord> records = new LinkedHashMap<>();
        store.findAll(hits.keySet()).forEach(r -> records.put(r.profileId(), r));
        Map<String, List<StoredIdentifier>> ids = store.identifiersOf(hits.keySet());
        List<ProfileSummary> items = new ArrayList<>();
        for (Map.Entry<String, MatchedBy> hit : hits.entrySet()) {
            ProfileRecord r = records.get(hit.getKey());
            if (r == null) {
                continue;
            }
            items.add(
                    new ProfileSummary(
                            r.profileId(),
                            hit.getValue(),
                            profiles.maskIdentifiers(
                                    ids.getOrDefault(r.profileId(), List.of()), new ArrayList<>()),
                            r.createdAt(),
                            r.updatedAt()));
        }
        items.sort(
                Comparator.comparing(ProfileSummary::matchedBy)
                        .thenComparing(ProfileSummary::updatedAt, Comparator.reverseOrder())
                        .thenComparing(ProfileSummary::profileId));
        return new ProfileSearchResponse(
                items.size() > MAX_ITEMS ? items.subList(0, MAX_ITEMS) : items);
    }

    private void lookup(IdentifierType type, String raw, Map<String, MatchedBy> hits) {
        Optional<String> value = normalizer.normalize(type, raw);
        if (value.isEmpty()) {
            return;
        }
        store.findByIdentifier(type, value.get())
                .ifPresent(id -> hits.putIfAbsent(id, MatchedBy.of(type)));
    }
}
