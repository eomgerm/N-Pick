package com.npick.member.infrastructure;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.npick.member.domain.model.Member;
import com.npick.member.domain.model.Role;
import com.npick.member.infrastructure.persistence.entity.MemberJpaEntity;
import com.npick.member.infrastructure.persistence.mapper.MemberPersistenceMapper;
import com.npick.member.infrastructure.persistence.repository.MemberJpaRepository;
import com.npick.member.infrastructure.persistence.repository.MemberRepositoryAdapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberRepositoryAdapterTest {

    @Mock
    MemberJpaRepository jpaRepository;

    MemberRepositoryAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new MemberRepositoryAdapter(jpaRepository, new MemberPersistenceMapper());
    }

    @Test
    @DisplayName("로그인아이디로 조회하면 엔티티를 도메인으로 매핑해 반환한다")
    void findByLoginIdMapsEntityToDomain() {
        when(jpaRepository.findByLoginId("reviewer01"))
                .thenReturn(Optional.of(new MemberJpaEntity(100L, "reviewer01", "$2a$hash", "박검수", "REVIEWER")));

        Optional<Member> found = adapter.findByLoginId("reviewer01");

        assertThat(found).isPresent();
        assertThat(found.get().memberId()).isEqualTo(100L);
        assertThat(found.get().role()).isEqualTo(Role.REVIEWER);
    }

    @Test
    @DisplayName("없는 로그인아이디는 빈값")
    void missingLoginIdReturnsEmpty() {
        when(jpaRepository.findByLoginId("none")).thenReturn(Optional.empty());

        assertThat(adapter.findByLoginId("none")).isEmpty();
    }
}
