package com.npick.member.infrastructure.persistence.mapper;

import org.springframework.stereotype.Component;

import com.npick.member.domain.model.Member;
import com.npick.member.domain.model.Role;
import com.npick.member.infrastructure.persistence.entity.MemberJpaEntity;

@Component
public class MemberPersistenceMapper {

    public MemberJpaEntity toEntity(Member member) {
        return new MemberJpaEntity(
                member.memberId(),
                member.loginId(),
                member.passwordHash(),
                member.name(),
                member.role().name());
    }

    public Member toDomain(MemberJpaEntity entity) {
        return new Member(
                entity.getMemberId(),
                entity.getLoginId(),
                entity.getName(),
                Role.valueOf(entity.getRole()),
                entity.getPasswordHash());
    }
}
