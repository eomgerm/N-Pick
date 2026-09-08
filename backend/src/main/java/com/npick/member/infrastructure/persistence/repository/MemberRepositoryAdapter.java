package com.npick.member.infrastructure.persistence.repository;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.npick.member.domain.model.Member;
import com.npick.member.domain.repository.MemberRepository;
import com.npick.member.infrastructure.persistence.mapper.MemberPersistenceMapper;

@Repository
public class MemberRepositoryAdapter implements MemberRepository {

    private final MemberJpaRepository jpaRepository;
    private final MemberPersistenceMapper mapper;

    public MemberRepositoryAdapter(MemberJpaRepository jpaRepository, MemberPersistenceMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    public Optional<Member> findByLoginId(String loginId) {
        return jpaRepository.findByLoginId(loginId).map(mapper::toDomain);
    }
}
