package com.npick.tag.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.npick.clip.infrastructure.persistence.entity.TagEvidenceJpaEntity;

/**
 * 검수자 태그 교정 후보 쓰기(S15P21A501-160). 읽기 전용인 {@code TagEvidenceJpaEntity}·해석기(-161)를 건드리지 않고 native {@code INSERT} 로 쓴다.
 *
 * <p>{@code tag}·{@code tagging} 은 {@code ON CONFLICT DO NOTHING} 으로 있으면 재사용, 없으면 생성한다(멱등, 예외 없음). {@code tag_evidence}
 * 는 {@code source='reviewer_feedback'}·{@code confirmed=false} 로 넣는다.
 */
public interface TagCorrectionCandidateJpaRepository extends JpaRepository<TagEvidenceJpaEntity, Long> {

    @Modifying(flushAutomatically = true)
    @Query(
            value = "INSERT INTO tag (tag_id, tag_type, match_value, name) "
                    + "VALUES (:id, :tagType, :matchValue, :name) "
                    + "ON CONFLICT (tag_type, match_value) DO NOTHING",
            nativeQuery = true)
    int insertTag(
            @Param("id") long id,
            @Param("tagType") String tagType,
            @Param("matchValue") String matchValue,
            @Param("name") String name);

    @Query(value = "SELECT tag_id FROM tag WHERE tag_type = :tagType AND match_value = :matchValue", nativeQuery = true)
    List<Long> findTagId(@Param("tagType") String tagType, @Param("matchValue") String matchValue);

    @Modifying(flushAutomatically = true)
    @Query(
            value = "INSERT INTO tagging (tagging_id, clip_id, scene_id, tag_id, created_at) "
                    + "VALUES (:id, :clipId, :sceneId, :tagId, :now) "
                    + "ON CONFLICT (clip_id, scene_id, tag_id) DO NOTHING",
            nativeQuery = true)
    int insertTagging(
            @Param("id") long id,
            @Param("clipId") long clipId,
            @Param("sceneId") Long sceneId,
            @Param("tagId") long tagId,
            @Param("now") Instant now);

    @Query(
            value = "SELECT tagging_id FROM tagging "
                    + "WHERE clip_id = :clipId AND scene_id IS NOT DISTINCT FROM :sceneId AND tag_id = :tagId",
            nativeQuery = true)
    List<Long> findTaggingId(@Param("clipId") long clipId, @Param("sceneId") Long sceneId, @Param("tagId") long tagId);

    @Modifying(flushAutomatically = true)
    @Query(
            value = "INSERT INTO tag_evidence (evidence_id, tagging_id, source, confidence, verification_status, "
                    + "source_feedback_id, confirmed, created_at) "
                    + "VALUES (:id, :taggingId, 'reviewer_feedback', NULL, :status, :feedbackId, false, :now)",
            nativeQuery = true)
    int insertEvidence(
            @Param("id") long id,
            @Param("taggingId") long taggingId,
            @Param("status") String verificationStatus,
            @Param("feedbackId") long sourceFeedbackId,
            @Param("now") Instant now);
}
