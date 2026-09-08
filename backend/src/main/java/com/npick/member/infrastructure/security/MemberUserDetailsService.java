package com.npick.member.infrastructure.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.npick.common.security.AuthenticatedMember;
import com.npick.member.domain.model.Member;
import com.npick.member.domain.repository.MemberRepository;

@Service
public class MemberUserDetailsService implements UserDetailsService {

    private final MemberRepository memberRepository;

    public MemberUserDetailsService(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String loginId) {
        Member member = memberRepository
                .findByLoginId(loginId)
                .orElseThrow(() -> new UsernameNotFoundException("member not found"));
        return new AuthenticatedMember(
                member.memberId(),
                member.loginId(),
                member.passwordHash(),
                member.role().name());
    }
}
