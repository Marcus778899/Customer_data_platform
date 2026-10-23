package com.cdp.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.cdp.common.web.ApiException;
import com.cdp.common.web.ErrorCode;

class UserServiceTest {

    private static final Instant T = Instant.parse("2026-10-22T00:00:00.123456789Z");

    private final AppUserStore store = mock(AppUserStore.class);
    private final PasswordHasher hasher = mock(PasswordHasher.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final UserService service =
            new UserService(store, hasher, () -> "01JD0USER0000000000000009", Clock.fixed(T, ZoneOffset.UTC), tx);

    @SuppressWarnings("unchecked")
    UserServiceTest() {
        when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));
        when(hasher.hash(any())).thenReturn("$2a$hashed");
    }

    private static AppUser user(String id, UserRole role, UserStatus status) {
        return new AppUser(id, id + "@example.com", "n", "$2a$old", role, status, T, T);
    }

    private static UpdateUserRequest patch(String role, String status) {
        return new UpdateUserRequest(null, role, status, null);
    }

    @Test
    void create_lowercases_email_truncates_time_to_micros_and_hashes_password() {
        UserResponse r = service.create(new CreateUserRequest(" Ops@Example.COM ", "Ops", "EDITOR", "correct-horse-battery"));
        assertThat(r.email()).isEqualTo("ops@example.com");
        assertThat(r.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(r.createdAt()).isEqualTo(Instant.parse("2026-10-22T00:00:00.123456Z"));
        verify(store).insert(any());
    }

    @Test
    void create_rejects_bad_input_collecting_all_fields_and_does_not_insert() {
        assertThatThrownBy(() -> service.create(new CreateUserRequest("nope", "", "ROOT", "short")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.errors()).extracting("field")
                            .containsExactly("email", "display_name", "role", "password");
                });
        verify(store, never()).insert(any());
    }

    @Test
    void duplicate_email_is_email_taken() {
        org.mockito.Mockito.doThrow(new DuplicateKeyException("dup")).when(store).insert(any());
        assertThatThrownBy(() -> service.create(new CreateUserRequest("a@example.com", "A", "VIEWER", "correct-horse-battery")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.EMAIL_TAKEN));
    }

    @Test
    void sole_active_admin_cannot_be_demoted_or_disabled_and_nothing_is_written() {
        when(store.lockActiveAdminIds()).thenReturn(List.of("a"));
        when(store.lockById("a")).thenReturn(Optional.of(user("a", UserRole.ADMIN, UserStatus.ACTIVE)));
        for (UpdateUserRequest req : List.of(patch("VIEWER", null), patch(null, "DISABLED"), patch("EDITOR", "DISABLED"))) {
            assertThatThrownBy(() -> service.update("a", req))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.code()).isEqualTo(ErrorCode.LAST_ADMIN);
                        assertThat(e.getMessage()).isEqualTo("至少要保留一位啟用中的管理員。");
                    });
        }
        verify(store, never()).update(any(), any(), any(), any(), any(), any());
    }

    @Test
    void admin_may_change_when_another_admin_remains_or_when_not_losing_admin() {
        when(store.lockActiveAdminIds()).thenReturn(List.of("a", "b"));
        when(store.lockById("a")).thenReturn(Optional.of(user("a", UserRole.ADMIN, UserStatus.ACTIVE)));
        when(store.findById("a")).thenReturn(Optional.of(user("a", UserRole.VIEWER, UserStatus.ACTIVE)));
        assertThat(service.update("a", patch("VIEWER", null)).role()).isEqualTo(UserRole.VIEWER);

        when(store.lockActiveAdminIds()).thenReturn(List.of("a"));
        when(store.findById("a")).thenReturn(Optional.of(user("a", UserRole.ADMIN, UserStatus.ACTIVE)));
        assertThat(service.update("a", new UpdateUserRequest("renamed", "ADMIN", "ACTIVE", null)).userId()).isEqualTo("a");
    }

    @Test
    void missing_user_is_404_and_bad_values_are_400_before_touching_the_database() {
        when(store.lockActiveAdminIds()).thenReturn(List.of());
        when(store.lockById("x")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update("x", patch(null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.USER_NOT_FOUND));
        assertThatThrownBy(() -> service.update("x", patch("ROOT", "DELETED")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errors()).extracting("field")
                        .containsExactly("role", "status"));
    }

    @Test
    void password_change_checks_current_password_and_rules() {
        AppUser u = user("a", UserRole.EDITOR, UserStatus.ACTIVE);
        when(store.findById("a")).thenReturn(Optional.of(u));
        when(hasher.matches("old-password-123", "$2a$old")).thenReturn(false);
        assertThatThrownBy(() -> service.changePassword("a", "old-password-123", "brand-new-password"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));
        assertThatThrownBy(() -> service.changePassword("a", "old-password-123", "short"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(store, never()).update(any(), any(), any(), any(), any(), any());
    }
}
