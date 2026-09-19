package com.npick.member.application.port;

import java.time.Instant;
import java.util.Optional;

public interface RefreshSessionStore {
    record StoredRefresh(String tokenHash, String loginId, String accessSessionId, Instant expiresAt) {}

    void insert(StoredRefresh refresh);

    Optional<StoredRefresh> lock(String tokenHash);

    void replaceAccess(String tokenHash, String accessSessionId);

    void delete(String tokenHash);

    void deleteExpired(Instant now);
}
