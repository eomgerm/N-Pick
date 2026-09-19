package com.npick.member.application.port;

import java.time.Instant;

import com.npick.member.application.command.login.LoginResult;

public interface AccessSessionPort {
    String create(LoginResult member, Instant notAfter);

    boolean isActive(String sessionId);

    void delete(String sessionId);
}
