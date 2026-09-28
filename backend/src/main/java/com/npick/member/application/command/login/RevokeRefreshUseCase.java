package com.npick.member.application.command.login;

public interface RevokeRefreshUseCase {
    void revoke(String token);
}
