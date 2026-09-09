package com.npick.feedback.infrastructure;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.npick.feedback.domain.model.Feedback;
import com.npick.feedback.domain.model.FeedbackResolution;
import com.npick.feedback.domain.model.FeedbackStatus;
import com.npick.feedback.infrastructure.persistence.entity.FeedbackJpaEntity;
import com.npick.feedback.infrastructure.persistence.mapper.FeedbackPersistenceMapper;
import com.npick.feedback.infrastructure.persistence.repository.FeedbackJpaRepository;
import com.npick.feedback.infrastructure.persistence.repository.FeedbackRepositoryAdapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedbackRepositoryAdapterTest {

    @Mock
    FeedbackJpaRepository jpaRepository;

    FeedbackRepositoryAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new FeedbackRepositoryAdapter(jpaRepository, new FeedbackPersistenceMapper());
    }

    @Test
    @DisplayName("claim은 open 갱신 영향 행수를 그대로 돌려준다")
    void claimReturnsAffectedRows() {
        when(jpaRepository.claim(eq(1L), eq(9L), any(Instant.class))).thenReturn(1);
        assertThat(adapter.claim(1L, 9L, Instant.EPOCH)).isEqualTo(1);
    }

    @Test
    @DisplayName("findById는 엔티티를 도메인으로 매핑한다")
    void findByIdMapsEntityToDomain() {
        when(jpaRepository.findById(1L))
                .thenReturn(Optional.of(new FeedbackJpaEntity(1L, 10L, 20L, "c", "OPEN", null, null)));
        Optional<Feedback> found = adapter.findById(1L);
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(FeedbackStatus.OPEN);
        assertThat(found.get().searchResultId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("종료성 판정은 CLOSED·closed_at(now)을 파생해 native 쿼리로 넘긴다")
    void resolveDerivesClosedForTerminal() {
        when(jpaRepository.resolve(
                        eq(1L), eq(9L), eq("no_action"), eq("사유"), eq("CLOSED"), eq(Instant.EPOCH), eq(Instant.EPOCH)))
                .thenReturn(1);
        int affected = adapter.resolve(1L, 9L, FeedbackResolution.NO_ACTION, "사유", Instant.EPOCH);
        assertThat(affected).isEqualTo(1);
    }

    @Test
    @DisplayName("교정 판정은 REVIEWING·closed_at=null을 파생한다")
    void resolveDerivesReviewingForCorrection() {
        when(jpaRepository.resolve(
                        eq(1L), eq(9L), eq("patch_parse"), isNull(), eq("REVIEWING"), isNull(), eq(Instant.EPOCH)))
                .thenReturn(1);
        int affected = adapter.resolve(1L, 9L, FeedbackResolution.PATCH_PARSE, null, Instant.EPOCH);
        assertThat(affected).isEqualTo(1);
    }
}
