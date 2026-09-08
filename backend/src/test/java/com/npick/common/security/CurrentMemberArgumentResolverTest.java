package com.npick.common.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.npick.common.security.resolver.CurrentMemberArgumentResolver;
import com.npick.common.security.resolver.LoginMember;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentMemberArgumentResolverTest {

    private final CurrentMemberArgumentResolver resolver = new CurrentMemberArgumentResolver();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @SuppressWarnings("unused")
    void handler(@LoginMember CurrentMember member) {}

    @Test
    @DisplayName("인증컨텍스트의 principal을 CurrentMember로 해석한다")
    void resolvesPrincipalFromContextToCurrentMember() throws Exception {
        AuthenticatedMember principal = new AuthenticatedMember(100L, "reviewer01", "$2a$hash", "REVIEWER");
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        MethodParameter param = new MethodParameter(getClass().getDeclaredMethod("handler", CurrentMember.class), 0);

        Object resolved = resolver.resolveArgument(param, null, null, null);

        assertThat(resolved).isInstanceOf(CurrentMember.class);
        CurrentMember current = (CurrentMember) resolved;
        assertThat(current.memberId()).isEqualTo(100L);
        assertThat(current.loginId()).isEqualTo("reviewer01");
        assertThat(current.role()).isEqualTo("REVIEWER");
    }
}
