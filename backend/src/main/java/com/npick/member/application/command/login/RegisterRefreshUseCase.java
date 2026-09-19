package com.npick.member.application.command.login;

import java.time.Instant;

public interface RegisterRefreshUseCase {
    IssuedRefresh register(String accessSessionId, LoginResult member);

    record IssuedRefresh(String token, Instant expiresAt) {}
}
