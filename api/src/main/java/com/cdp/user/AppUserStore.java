package com.cdp.user;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** app_user 的 JDBC 存取。鎖定查詢（FOR UPDATE）必須在呼叫端的交易內執行。 */
@Repository
public class AppUserStore {

    private static final String COLUMNS =
            "user_id, email, display_name, password_hash, role, status, created_at, updated_at, session_epoch";

    private final JdbcClient jdbc;

    public AppUserStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** email 重複時丟 {@link org.springframework.dao.DuplicateKeyException}。 */
    public void insert(AppUser u) {
        jdbc.sql("insert into app_user (" + COLUMNS + ") values"
                        + " (:id, :email, :name, :hash, :role, :status, :createdAt, :updatedAt, :epoch)")
                .param("epoch", u.sessionEpoch())
                .param("id", u.userId())
                .param("email", u.email())
                .param("name", u.displayName())
                .param("hash", u.passwordHash())
                .param("role", u.role().name())
                .param("status", u.status().name())
                .param("createdAt", ts(u.createdAt()))
                .param("updatedAt", ts(u.updatedAt()))
                .update();
    }

    public Optional<AppUser> findById(String userId) {
        return jdbc.sql("select " + COLUMNS + " from app_user where user_id = :id")
                .param("id", userId)
                .query(AppUserStore::map)
                .optional();
    }

    public Optional<AppUser> findByEmail(String email) {
        return jdbc.sql("select " + COLUMNS + " from app_user where email = :email")
                .param("email", email)
                .query(AppUserStore::map)
                .optional();
    }

    public List<AppUser> findAllByEmail() {
        return jdbc.sql("select " + COLUMNS + " from app_user order by email")
                .query(AppUserStore::map)
                .list();
    }

    /** 鎖定並回傳目標列（交易內）。 */
    public Optional<AppUser> lockById(String userId) {
        return jdbc.sql("select " + COLUMNS + " from app_user where user_id = :id for update")
                .param("id", userId)
                .query(AppUserStore::map)
                .optional();
    }

    /** 鎖定所有啟用中的 ADMIN（固定以 user_id 排序取鎖，避免死結），回傳其 user_id（交易內）。 */
    public List<String> lockActiveAdminIds() {
        return jdbc.sql("select user_id from app_user where role = 'ADMIN' and status = 'ACTIVE'"
                        + " order by user_id for update")
                .query(String.class)
                .list();
    }

    public void update(
            String userId, String displayName, UserRole role, UserStatus status, String passwordHash,
            boolean bumpEpoch,
            Instant at) {
        jdbc.sql("update app_user set display_name = :name, role = :role, status = :status,"
                        + " password_hash = :hash, updated_at = :at,"
                        + " session_epoch = session_epoch + (case when :bump then 1 else 0 end)"
                        + " where user_id = :id")
                .param("bump", bumpEpoch)
                .param("name", displayName)
                .param("role", role.name())
                .param("status", status.name())
                .param("hash", passwordHash)
                .param("at", ts(at))
                .param("id", userId)
                .update();
    }

    private static AppUser map(ResultSet rs, int row) throws SQLException {
        return new AppUser(
                rs.getString("user_id"),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("password_hash"),
                UserRole.valueOf(rs.getString("role")),
                UserStatus.valueOf(rs.getString("status")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                rs.getInt("session_epoch"));
    }

    private static OffsetDateTime ts(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
