package com.npick.member.infrastructure.persistence.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.npick.member.infrastructure.persistence.entity.MemberJpaEntity;

public interface MemberJpaRepository extends JpaRepository<MemberJpaEntity, Long> {

    Optional<MemberJpaEntity> findByLoginId(String loginId);
}
