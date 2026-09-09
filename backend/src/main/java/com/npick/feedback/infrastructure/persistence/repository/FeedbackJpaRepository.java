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

    @Query(value = "SELECT count(*) > 0 FROM search_result WHERE search_result_id = :id", nativeQuery = true)
    boolean existsSearchResult(@Param("id") long searchResultId);

    @Modifying
    @Query("UPDATE FeedbackJpaEntity f SET f.status = 'REVIEWING', f.reviewedById = :reviewerId, "
            + "f.reviewStartedAt = :startedAt, f.updatedAt = :startedAt WHERE f.feedbackId = :id AND f.status = 'OPEN'")
    int claim(
            @Param("id") long feedbackId,
            @Param("reviewerId") long reviewerId,
            @Param("startedAt") Instant reviewStartedAt);

    @Modifying
    @Query("UPDATE FeedbackJpaEntity f SET f.comment = :comment, f.updatedAt = :now WHERE f.feedbackId = :id "
            + "AND f.createdById = :ownerId AND f.status = 'OPEN'")
    int editComment(
            @Param("id") long feedbackId,
            @Param("ownerId") long ownerId,
            @Param("comment") String comment,
            @Param("now") Instant now);

    // resolution 은 소문자, closed_at 은 종료성 판정일 때만 채운다. JPA 엔티티에 없는 컬럼(resolution·resolution_note·closed_at)이라 native 로 쓴다.
    @Modifying
    @Query(
            value = "UPDATE feedback SET resolution = :resolution, resolution_note = :note, status = :newStatus, "
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
}
