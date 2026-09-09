package com.npick.member.application.command.login;

public interface LoginUseCase {
    LoginResult login(LoginCommand command);
}
