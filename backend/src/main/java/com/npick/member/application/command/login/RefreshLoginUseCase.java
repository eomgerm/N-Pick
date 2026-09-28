package com.npick.member.application.command.login;

public interface RefreshLoginUseCase {
    RefreshedLogin refresh(String token);

    record RefreshedLogin(String accessSessionId, LoginResult member) {}
}
