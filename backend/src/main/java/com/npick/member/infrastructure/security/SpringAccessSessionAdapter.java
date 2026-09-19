package com.npick.member.infrastructure.security;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Component;

import com.npick.common.security.AuthenticatedMember;
import com.npick.common.security.config.AccessSessionExpiryFilter;
import com.npick.member.application.command.login.LoginResult;
import com.npick.member.application.port.AccessSessionPort;

@Component
public class SpringAccessSessionAdapter implements AccessSessionPort {
    private final SessionRepository<?> sessions;
    private final Duration lifetime;

    public SpringAccessSessionAdapter(
            SessionRepository<?> sessions, @Value("${server.servlet.session.timeout:30m}") Duration lifetime) {
        this.sessions = sessions;
        this.lifetime = lifetime;
    }

    @Override
    public String create(LoginResult member, Instant notAfter) {
        return createSession(sessions, member, notAfter);
    }

    private <S extends Session> String createSession(
            SessionRepository<S> repository, LoginResult member, Instant notAfter) {
        S session = repository.createSession();
        var principal = new AuthenticatedMember(member.memberId(), member.loginId(), null, member.role());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        session.setAttribute(
                AccessSessionExpiryFilter.EXPIRES_AT,
                Math.min(Instant.now().plus(lifetime).toEpochMilli(), notAfter.toEpochMilli()));
        repository.save(session);
        return session.getId();
    }

    @Override
    public boolean isActive(String sessionId) {
        var session = sessions.findById(sessionId);
        if (session == null) return false;
        Long expires = session.getAttribute(AccessSessionExpiryFilter.EXPIRES_AT);
        return expires != null && expires > System.currentTimeMillis();
    }

    @Override
    public void delete(String sessionId) {
        sessions.deleteById(sessionId);
    }
}
