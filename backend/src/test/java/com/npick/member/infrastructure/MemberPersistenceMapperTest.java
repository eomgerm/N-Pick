package com.npick.member.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.member.domain.model.Member;
import com.npick.member.domain.model.Role;
import com.npick.member.infrastructure.persistence.entity.MemberJpaEntity;
import com.npick.member.infrastructure.persistence.mapper.MemberPersistenceMapper;

import static org.assertj.core.api.Assertions.assertThat;

class MemberPersistenceMapperTest {

    private final MemberPersistenceMapper mapper = new MemberPersistenceMapper();

    @Test
    @DisplayName("도메인을 엔티티로 변환한다")
    void mapsDomainToEntity() {
        MemberJpaEntity entity = mapper.toEntity(new Member(100L, "reviewer01", "박검수", Role.REVIEWER, "$2a$hash"));

        assertThat(entity.getMemberId()).isEqualTo(100L);
        assertThat(entity.getLoginId()).isEqualTo("reviewer01");
        assertThat(entity.getName()).isEqualTo("박검수");
        assertThat(entity.getRole()).isEqualTo("REVIEWER");
        assertThat(entity.getPasswordHash()).isEqualTo("$2a$hash");
    }

    @Test
    @DisplayName("엔티티를 도메인으로 변환한다")
    void mapsEntityToDomain() {
        Member member = mapper.toDomain(new MemberJpaEntity(100L, "reviewer01", "$2a$hash", "박검수", "REVIEWER"));

        assertThat(member.memberId()).isEqualTo(100L);
        assertThat(member.role()).isEqualTo(Role.REVIEWER);
        assertThat(member.passwordHash()).isEqualTo("$2a$hash");
    }
}
