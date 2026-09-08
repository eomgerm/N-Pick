package com.npick.member.domain.repository;

import java.util.Optional;

import com.npick.member.domain.model.Member;

public interface MemberRepository {

    Optional<Member> findByLoginId(String loginId);
}
