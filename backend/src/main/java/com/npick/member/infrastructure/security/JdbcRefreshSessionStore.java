package com.npick.member.infrastructure.security;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.npick.member.application.port.RefreshSessionStore;

@Repository
public class JdbcRefreshSessionStore implements RefreshSessionStore {
    private final JdbcTemplate jdbc;

    public JdbcRefreshSessionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(StoredRefresh refresh) {
        jdbc.update(
                "INSERT INTO npick.login_refresh (token_hash, login_id, access_session_id, expires_at) VALUES (?, ?, ?, ?)",
                refresh.tokenHash(),
                refresh.loginId(),
                refresh.accessSessionId(),
                Timestamp.from(refresh.expiresAt()));
    }

    @Override
    public Optional<StoredRefresh> lock(String tokenHash) {
        return jdbc
                .query(
                        "SELECT token_hash, login_id, access_session_id, expires_at FROM npick.login_refresh WHERE token_hash = ? FOR UPDATE",
                        (row, index) -> new StoredRefresh(
                                row.getString(1),
                                row.getString(2),
                                row.getString(3),
                                row.getTimestamp(4).toInstant()),
                        tokenHash)
                .stream()
                .findFirst();
    }

    @Override
    public void replaceAccess(String tokenHash, String accessSessionId) {
        jdbc.update(
                "UPDATE npick.login_refresh SET access_session_id = ? WHERE token_hash = ?",
                accessSessionId,
                tokenHash);
    }

    @Override
    public void delete(String tokenHash) {
        jdbc.update("DELETE FROM npick.login_refresh WHERE token_hash = ?", tokenHash);
    }

    @Override
    public void deleteExpired(Instant now) {
        jdbc.update("DELETE FROM npick.login_refresh WHERE expires_at <= ?", Timestamp.from(now));
    }
}
