package com.npick.member.application.command;

import org.springframework.stereotype.Service;

import com.npick.common.error.BusinessException;
import com.npick.member.application.command.login.LoginCommand;
import com.npick.member.application.command.login.LoginResult;
import com.npick.member.application.command.login.LoginUseCase;
import com.npick.member.application.port.MemberAuthenticationPort;
import com.npick.member.domain.error.MemberErrorCode;

@Service
public class MemberLoginService implements LoginUseCase {
    private final MemberAuthenticationPort authentication;

    public MemberLoginService(MemberAuthenticationPort authentication) {
        this.authentication = authentication;
    }

    @Override
    public LoginResult login(LoginCommand command) {
        return authentication
                .authenticate(command)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.INVALID_CREDENTIALS));
    }
}
