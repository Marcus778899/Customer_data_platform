package com.cdp.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.cdp.common.web.ApiException;
import com.cdp.common.web.ErrorCode;
import com.cdp.query.Page;
import com.cdp.user.AppUser;
import com.cdp.user.AppUserStore;
import com.cdp.user.UserRole;
import com.cdp.user.UserStatus;

class JourneyQueryAndKeysTest {

    private static final Instant NOW = Instant.parse("2026-11-08T01:00:00Z");

    private final JourneyQueryStore store = mock(JourneyQueryStore.class);
    private final JourneyDefinitionStore definitions = mock(JourneyDefinitionStore.class);
    private final JourneyStore journeys = mock(JourneyStore.class);
    private final JourneyQueryService query = new JourneyQueryService(store, definitions, journeys);
    private final FieldKeysService keys = new FieldKeysService(journeys, Clock.fixed(NOW, ZoneOffset.UTC));

    private static JourneySummary summary(int i) {
        return new JourneySummary("j" + i, "n" + i, JourneyStatus.ACTIVE, 1, false, null, NOW, NOW, null);
    }

    @Test
    void list_rejectsBadFilters() {
        assertThatThrownBy(() -> query.list("NOPE", null, null, null, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> query.list(null, null, "APPROVED", null, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> query.list(null, "a\0b", null, null, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> query.list(null, null, null, null, 0)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> query.list(null, null, null, null, 101)).isInstanceOf(ApiException.class);
    }

    @Test
    void list_returnsNextCursorOnlyWhenMoreRows() {
        List<JourneySummary> three = new ArrayList<>(List.of(summary(1), summary(2), summary(3)));
        when(store.list(any(), any(), any(), any(), eq(3))).thenReturn(three);
        Page<JourneySummary> page = query.list("ACTIVE, DRAFT", " ", "PENDING", null, 2);
        assertThat(page.items()).hasSize(2);
        assertThat(page.nextCursor()).isNotNull();

        when(store.list(any(), any(), any(), any(), eq(21))).thenReturn(List.of(summary(1)));
        assertThat(query.list(null, "x", null, null, null).nextCursor()).isNull();
    }

    @Test
    void detailAndVersion_notFound() {
        when(store.detail("x")).thenReturn(Optional.empty());
        when(definitions.find("x", 1)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> query.detail("x"))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.JOURNEY_NOT_FOUND));
        assertThatThrownBy(() -> query.version("x", 1)).isInstanceOf(ApiException.class);
    }

    @Test
    void fieldKeys_withoutEventName_skipsEventLookup() {
        when(journeys.profileAttributeKeys(anyInt())).thenReturn(List.of("a"));
        FieldKeys k = keys.fieldKeys(null);
        assertThat(k.profileAttributes()).containsExactly("a");
        assertThat(k.eventProperties()).isEmpty();
        assertThat(k.asOf()).isEqualTo(NOW);
        verify(journeys, never()).eventPropertyKeys(any(), anyInt());
    }

    @Test
    void fieldKeys_withEventName_andValidation() {
        when(journeys.eventPropertyKeys("e", 1000)).thenReturn(List.of("p"));
        assertThat(keys.fieldKeys("e").eventProperties()).containsExactly("p");
        assertThatThrownBy(() -> keys.fieldKeys("a\0")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> keys.fieldKeys("x".repeat(201))).isInstanceOf(ApiException.class);
    }

    @Test
    void userRefs_nullKnownAndUnknown() {
        AppUserStore users = mock(AppUserStore.class);
        AppUser u = new AppUser("u1", "a@b.c", "小明", "h", UserRole.EDITOR, UserStatus.DISABLED, NOW, NOW, 0);
        when(users.findById("u1")).thenReturn(Optional.of(u));
        when(users.findById("u2")).thenReturn(Optional.empty());
        UserRefs refs = new UserRefs(users);
        assertThat(refs.of(null)).isNull();
        assertThat(refs.of("u1")).isEqualTo(new UserRef("u1", "小明"));
        assertThat(refs.of("u2")).isEqualTo(new UserRef("u2", "u2"));
    }
}
