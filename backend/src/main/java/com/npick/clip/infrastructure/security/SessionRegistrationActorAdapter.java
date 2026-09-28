package com.npick.clip.infrastructure.security;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.npick.clip.application.port.RegistrationActorPort;
import com.npick.common.error.BusinessException;
import com.npick.common.error.CommonErrorCode;
import com.npick.common.security.AuthenticatedMember;

@Component
public final class SessionRegistrationActorAdapter implements RegistrationActorPort {
    @Override
    public long requireReviewerId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedMember member)
                || member.memberId() <= 0) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
        if (!"REVIEWER".equals(member.role())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return member.memberId();
    }
}
