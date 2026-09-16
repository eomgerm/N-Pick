package com.npick.tag.infrastructure.persistence.repository;

import java.util.Collection;

import org.springframework.stereotype.Repository;

import com.npick.tag.domain.repository.TagCorrectionConfirmationRepository;

/** 태그 교정 후보 확정 쓰기 어댑터(S15P21A501-84). native {@code UPDATE} 한 방으로 신고 범위의 미확정 근거를 확정한다. */
@Repository
public class TagCorrectionConfirmationRepositoryAdapter implements TagCorrectionConfirmationRepository {

    private final TagCorrectionConfirmationJpaRepository jpaRepository;

    public TagCorrectionConfirmationRepositoryAdapter(TagCorrectionConfirmationJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public int confirm(long sourceFeedbackId, Collection<Long> evidenceIds) {
        if (evidenceIds.isEmpty()) {
            return 0;
        }
        return jpaRepository.confirm(sourceFeedbackId, evidenceIds);
    }
}
