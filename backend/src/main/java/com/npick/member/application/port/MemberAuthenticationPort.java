package com.npick.member.application.port;

import java.util.Optional;

import com.npick.member.application.command.login.LoginCommand;
import com.npick.member.application.command.login.LoginResult;

public interface MemberAuthenticationPort {
    Optional<LoginResult> authenticate(LoginCommand command);
}
