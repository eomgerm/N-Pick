package com.npick.feedback.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.npick.feedback.infrastructure.persistence.entity.FeedbackJpaEntity;

public interface FeedbackJpaRepository extends JpaRepository<FeedbackJpaEntity, Long> {

    Optional<FeedbackJpaEntity> findBySearchResultIdAndCreatedById(long searchResultId, long createdById);

    @Query(
            value = "SELECT count(*) > 0 FROM search_result sr"
                    + " JOIN search_execution se ON se.search_execution_id = sr.search_execution_id"
                    + " WHERE sr.search_result_id = :id AND se.searched_by_id = :searchedById",
            nativeQuery = true)
    boolean existsSearchResultSearchedBy(@Param("id") long searchResultId, @Param("searchedById") long searchedById);

    @Modifying
    @Query("UPDATE FeedbackJpaEntity f SET f.status = 'REVIEWING', f.reviewedById = :reviewerId, "
            + "f.reviewStartedAt = :startedAt, f.updatedAt = :startedAt WHERE f.feedbackId = :id AND f.status = 'OPEN'")
    int claim(
            @Param("id") long feedbackId,
            @Param("reviewerId") long reviewerId,
            @Param("startedAt") Instant reviewStartedAt);

    // 검수 취소(S15P21A501-289). resolution·resolution_note 는 JPA 엔티티에 없는 컬럼이라 native 로 쓴다.
    // 검수 중 기록한 판정·사유는 이 담당자의 진행 중 판단이라 비워, 다음 검수자가 처음부터 판단하게 한다.
    @Modifying
    @Query(
            value = "UPDATE feedback SET status = 'OPEN', reviewed_by_id = NULL, review_started_at = NULL, "
                    + "resolution = NULL, resolution_note = NULL, updated_at = :now "
                    + "WHERE feedback_id = :id AND status = 'REVIEWING' AND reviewed_by_id = :reviewerId",
            nativeQuery = true)
    int release(@Param("id") long feedbackId, @Param("reviewerId") long reviewerId, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE FeedbackJpaEntity f SET f.comment = :comment, f.updatedAt = :now WHERE f.feedbackId = :id "
            + "AND f.createdById = :ownerId AND f.status = 'OPEN'")
    int editComment(
            @Param("id") long feedbackId,
            @Param("ownerId") long ownerId,
            @Param("comment") String comment,
            @Param("now") Instant now);

    // resolution 은 소문자, closed_at 은 종료성 판정일 때만 채운다. JPA 엔티티에 없는 컬럼(resolution·resolution_note·closed_at)이라 native 로 쓴다.
    // note 를 생략하면(교정 3종은 선택) 기존 사유를 유지한다 — F-09 "어느 경우에도 이유 확인 가능". 사유를 비우려면 재판정이 아니라 별도 경로가 필요하다.
    @Modifying
    @Query(
            value = "UPDATE feedback SET resolution = :resolution, "
                    + "resolution_note = COALESCE(:note, resolution_note), status = :newStatus, "
                    + "closed_at = :closedAt, updated_at = :now "
                    + "WHERE feedback_id = :id AND status = 'REVIEWING' AND reviewed_by_id = :reviewerId",
            nativeQuery = true)
    int resolve(
            @Param("id") long feedbackId,
            @Param("reviewerId") long reviewerId,
            @Param("resolution") String resolution,
            @Param("note") String note,
            @Param("newStatus") String newStatus,
            @Param("closedAt") Instant closedAt,
            @Param("now") Instant now);

    // 교정 확정(S15P21A501-84). verified_by_execution_id·created_rule_id·closed_at 은 JPA 엔티티에 없는 컬럼이라 native 로 쓴다.
    // created_rule_id 는 최종 승인한 교정 규칙(patch_parse)이고 태그만 교정하면 NULL 이다(baseline 주석·F-13).
    // expected_resolution CAS 로 조회~확정 사이 판정 변경(PUT resolution)을 잡는다 — 검증한 종류와 다른 판정으로 바뀌면 0 행이라 확정이 거부된다.
    // resolution 은 new_resolution 으로 갱신한다(S15P21A501-281) — 세 세부 종류(tag_correction/patch_parse/exclude_scene)를
    // 확정 시점에 단일화된 correction 값으로 남긴다. WHERE 는 여전히 확정 전 값(expected_resolution)으로 CAS 를 건다.
    @Modifying
    @Query(
            value = "UPDATE feedback SET status = 'CLOSED', verified_by_execution_id = :executionId, "
                    + "created_rule_id = :createdRuleId, resolution = :newResolution, closed_at = :now, updated_at = :now "
                    + "WHERE feedback_id = :id AND status = 'REVIEWING' AND reviewed_by_id = :reviewerId "
                    + "AND resolution = :expectedResolution",
            nativeQuery = true)
    int confirm(
            @Param("id") long feedbackId,
            @Param("reviewerId") long reviewerId,
            @Param("executionId") long executionId,
            @Param("createdRuleId") Long createdRuleId,
            @Param("expectedResolution") String expectedResolution,
            @Param("newResolution") String newResolution,
            @Param("now") Instant now);
}
