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
}
