package com.npick.tag.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.npick.common.persistence.TsidGenerator;
import com.npick.tag.domain.model.ReviewerTagJudgment;
import com.npick.tag.domain.repository.TagCorrectionCandidateRepository;

/**
 * 검수자 태그 교정 후보 쓰기(S15P21A501-160). 읽기 전용인 {@code tag_evidence} 해석기(-161)를 건드리지 않고 native {@code INSERT} 로 쓴다.
 *
 * <p>{@code tag}·{@code tagging} 은 {@code ON CONFLICT DO NOTHING} 으로 있으면 재사용, 없으면 생성한다(멱등, 예외 없음). {@code tag_evidence}
 * 는 {@code source='reviewer_feedback'}·{@code confirmed=false} 로 넣는다.
 *
 * <p><b>Spring Data 리포지터리를 두지 않는 이유는 모듈 경계다</b> (S15P21A501-201). 쿼리가 전부 native 라 엔티티는 {@code JpaRepository<T, ID>} 의
 * 타입 파라미터로만 쓰였는데, 그 자리에 {@code clip} 의 {@code TagEvidenceJpaEntity} 를 넣으면 {@code tag} 가 남의 모듈 infrastructure 를 참조하게
 * 된다({@code ModuleBoundaryArchitectureTest}). {@link EntityManager} 로 직접 쓰면 같은 영속성 컨텍스트·트랜잭션을 유지하면서 그 참조가 사라진다. 쓰기 전
 * {@code flush} 는 기존 {@code @Modifying(flushAutomatically = true)} 를 그대로 옮긴 것이다.
 */
@Repository
public class TagCorrectionCandidateRepositoryAdapter implements TagCorrectionCandidateRepository {

    private final EntityManager entityManager;

    public TagCorrectionCandidateRepositoryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    // 여러 INSERT 를 한 트랜잭션으로 묶는다. tag·tagging 은 ON CONFLICT DO NOTHING 이라 예외가 없어 바깥 트랜잭션에 합류해도 안전하다.
    @Override
    @Transactional
    public long addJudgment(ReviewerTagJudgment judgment) {
        long tagId = ensureTagId(judgment);
        long taggingId = ensureTaggingId(judgment, tagId);
        long evidenceId = TsidGenerator.generate();
        entityManager.flush();
        entityManager
                .createNativeQuery("INSERT INTO tag_evidence (evidence_id, tagging_id, source, confidence, "
                        + "verification_status, source_feedback_id, confirmed, created_at) "
                        + "VALUES (:id, :taggingId, 'reviewer_feedback', NULL, :status, :feedbackId, false, :now)")
                .setParameter("id", evidenceId)
                .setParameter("taggingId", taggingId)
                .setParameter("status", judgment.verificationStatus())
                .setParameter("feedbackId", judgment.sourceFeedbackId())
                .setParameter("now", Instant.now())
                .executeUpdate();
        return evidenceId;
    }

    // 확정(-84)은 confirmed 만 올리고 행을 지우지 않으므로 confirmed 여부와 무관하게 이 신고가 만든 근거를 전부 센다.
    @Override
    public int countByFeedback(long sourceFeedbackId) {
        Number count = (Number) entityManager
                .createNativeQuery("SELECT count(*) FROM tag_evidence "
                        + "WHERE source_feedback_id = :feedbackId AND source = 'reviewer_feedback'")
                .setParameter("feedbackId", sourceFeedbackId)
                .getSingleResult();
        return count.intValue();
    }

    private long ensureTagId(ReviewerTagJudgment judgment) {
        entityManager.flush();
        entityManager
                .createNativeQuery("INSERT INTO tag (tag_id, tag_type, match_value, name) "
                        + "VALUES (:id, :tagType, :matchValue, :name) "
                        + "ON CONFLICT (tag_type, match_value) DO NOTHING")
                .setParameter("id", TsidGenerator.generate())
                .setParameter("tagType", judgment.tagType())
                .setParameter("matchValue", judgment.matchValue())
                .setParameter("name", judgment.displayName())
                .executeUpdate();
        Query lookup = entityManager
                .createNativeQuery("SELECT tag_id FROM tag WHERE tag_type = :tagType AND match_value = :matchValue")
                .setParameter("tagType", judgment.tagType())
                .setParameter("matchValue", judgment.matchValue());
        // 방금 ON CONFLICT DO NOTHING 으로 넣었는데 곧바로 조회가 비었다 — 어떤 태그였는지 남겨 진단할 수 있게 한다.
        return firstId(
                lookup,
                () -> "tag upsert 후 조회 실패: tagType=" + judgment.tagType() + " matchValue=" + judgment.matchValue());
    }

    private long ensureTaggingId(ReviewerTagJudgment judgment, long tagId) {
        entityManager.flush();
        entityManager
                .createNativeQuery("INSERT INTO tagging (tagging_id, clip_id, scene_id, tag_id, created_at) "
                        + "VALUES (:id, :clipId, :sceneId, :tagId, :now) "
                        + "ON CONFLICT (clip_id, scene_id, tag_id) DO NOTHING")
                .setParameter("id", TsidGenerator.generate())
                .setParameter("clipId", judgment.clipId())
                .setParameter("sceneId", judgment.sceneId())
                .setParameter("tagId", tagId)
                .setParameter("now", Instant.now())
                .executeUpdate();
        Query lookup = entityManager
                .createNativeQuery("SELECT tagging_id FROM tagging "
                        + "WHERE clip_id = :clipId AND scene_id IS NOT DISTINCT FROM :sceneId AND tag_id = :tagId")
                .setParameter("clipId", judgment.clipId())
                .setParameter("sceneId", judgment.sceneId())
                .setParameter("tagId", tagId);
        return firstId(
                lookup,
                () -> "tagging upsert 후 조회 실패: clipId=" + judgment.clipId() + " sceneId=" + judgment.sceneId()
                        + " tagId=" + tagId);
    }

    private static long firstId(Query query, Supplier<String> absent) {
        List<?> rows = query.getResultList();
        if (rows.isEmpty()) throw new IllegalStateException(absent.get());
        return ((Number) rows.get(0)).longValue();
    }
}
