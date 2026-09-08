package com.npick.member.infrastructure.security;

import java.util.Optional;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

import com.npick.common.security.AuthenticatedMember;
import com.npick.member.application.command.login.LoginCommand;
import com.npick.member.application.command.login.LoginResult;
import com.npick.member.application.port.MemberAuthenticationPort;

@Component
public class MemberAuthenticationAdapter implements MemberAuthenticationPort {
    private final AuthenticationManager authenticationManager;

    public MemberAuthenticationAdapter(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    @Override
    public Optional<LoginResult> authenticate(LoginCommand command) {
        try {
            var authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(command.loginId(), command.password()));
            var principal = (AuthenticatedMember) authentication.getPrincipal();
            return Optional.of(new LoginResult(principal.memberId(), authentication.getName(), principal.role()));
        } catch (BadCredentialsException | UsernameNotFoundException failure) {
            return Optional.empty();
        }
    }
}
