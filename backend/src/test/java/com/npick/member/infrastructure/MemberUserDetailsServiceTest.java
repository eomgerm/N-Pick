package com.npick.member.infrastructure;

import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import com.npick.common.security.AuthenticatedMember;
import com.npick.member.domain.model.Member;
import com.npick.member.domain.model.Role;
import com.npick.member.domain.repository.MemberRepository;
import com.npick.member.infrastructure.security.MemberUserDetailsService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberUserDetailsServiceTest {

    @Mock
    MemberRepository memberRepository;

    @InjectMocks
    MemberUserDetailsService service;

    @Test
    @DisplayName("로그인아이디로 인증주체를 로드하고 역할권한을 부여한다")
    void loadsPrincipalByLoginIdAndGrantsRoleAuthority() {
        when(memberRepository.findByLoginId("reviewer01"))
                .thenReturn(Optional.of(new Member(100L, "reviewer01", "박검수", Role.REVIEWER, "$2a$hash")));

        UserDetails details = service.loadUserByUsername("reviewer01");

        assertThat(details).isInstanceOf(AuthenticatedMember.class);
        assertThat(details.getUsername()).isEqualTo("reviewer01");
        assertThat(details.getPassword()).isEqualTo("$2a$hash");
        assertThat(details.getAuthorities()).extracting("authority").containsExactly("ROLE_REVIEWER");
        assertThat(((AuthenticatedMember) details).memberId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("없는 계정은 UsernameNotFoundException")
    void missingAccountThrowsUsernameNotFoundException() {
        when(memberRepository.findByLoginId("none")).thenReturn(Optional.empty());

        Assertions.assertThatThrownBy(() -> service.loadUserByUsername("none"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
