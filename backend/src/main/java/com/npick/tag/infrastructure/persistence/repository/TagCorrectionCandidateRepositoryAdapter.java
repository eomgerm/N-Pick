package com.npick.tag.infrastructure.persistence.repository;

import java.time.Instant;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.npick.common.persistence.TsidGenerator;
import com.npick.tag.domain.model.ReviewerTagJudgment;
import com.npick.tag.domain.repository.TagCorrectionCandidateRepository;

@Repository
public class TagCorrectionCandidateRepositoryAdapter implements TagCorrectionCandidateRepository {

    private final TagCorrectionCandidateJpaRepository jpaRepository;

    public TagCorrectionCandidateRepositoryAdapter(TagCorrectionCandidateJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    // 여러 INSERT 를 한 트랜잭션으로 묶는다. tag·tagging 은 ON CONFLICT DO NOTHING 이라 예외가 없어 바깥 트랜잭션에 합류해도 안전하다.
    @Override
    @Transactional
    public long addJudgment(ReviewerTagJudgment judgment) {
        long tagId = ensureTagId(judgment);
        long taggingId = ensureTaggingId(judgment, tagId);
        long evidenceId = TsidGenerator.generate();
        jpaRepository.insertEvidence(
                evidenceId, taggingId, judgment.verificationStatus(), judgment.sourceFeedbackId(), Instant.now());
        return evidenceId;
    }

    private long ensureTagId(ReviewerTagJudgment judgment) {
        long id = TsidGenerator.generate();
        jpaRepository.insertTag(id, judgment.tagType(), judgment.matchValue(), judgment.displayName());
        return jpaRepository.findTagId(judgment.tagType(), judgment.matchValue()).stream()
                .findFirst()
                // 방금 ON CONFLICT DO NOTHING 으로 넣었는데 곧바로 조회가 비었다 — 어떤 태그였는지 남겨 진단할 수 있게 한다.
                .orElseThrow(() -> new IllegalStateException(
                        "tag upsert 후 조회 실패: tagType=" + judgment.tagType() + " matchValue=" + judgment.matchValue()));
    }

    private long ensureTaggingId(ReviewerTagJudgment judgment, long tagId) {
        long id = TsidGenerator.generate();
        jpaRepository.insertTagging(id, judgment.clipId(), judgment.sceneId(), tagId, Instant.now());
        return jpaRepository.findTaggingId(judgment.clipId(), judgment.sceneId(), tagId).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("tagging upsert 후 조회 실패: clipId=" + judgment.clipId()
                        + " sceneId=" + judgment.sceneId() + " tagId=" + tagId));
    }
}
