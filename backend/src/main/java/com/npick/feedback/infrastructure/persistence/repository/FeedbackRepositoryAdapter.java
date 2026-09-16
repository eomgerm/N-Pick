package com.npick.feedback.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.npick.feedback.domain.model.Feedback;
import com.npick.feedback.domain.model.FeedbackResolution;
import com.npick.feedback.domain.model.FeedbackStatus;
import com.npick.feedback.domain.repository.FeedbackRepository;
import com.npick.feedback.infrastructure.persistence.mapper.FeedbackPersistenceMapper;

@Repository
public class FeedbackRepositoryAdapter implements FeedbackRepository {

    private final FeedbackJpaRepository jpaRepository;
    private final FeedbackPersistenceMapper mapper;

    public FeedbackRepositoryAdapter(FeedbackJpaRepository jpaRepository, FeedbackPersistenceMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    public Feedback save(Feedback feedback) {
        return mapper.toDomain(jpaRepository.save(mapper.toEntity(feedback)));
    }

    @Override
    public Optional<Feedback> findById(long feedbackId) {
        return jpaRepository.findById(feedbackId).map(mapper::toDomain);
    }

    @Override
    public Optional<Feedback> findByResultAndCreator(long searchResultId, long createdById) {
        return jpaRepository
                .findBySearchResultIdAndCreatedById(searchResultId, createdById)
                .map(mapper::toDomain);
    }

    @Override
    public boolean existsSearchResult(long searchResultId) {
        return jpaRepository.existsSearchResult(searchResultId);
    }

    @Override
    public int claim(long feedbackId, long reviewerId, Instant reviewStartedAt) {
        return jpaRepository.claim(feedbackId, reviewerId, reviewStartedAt);
    }

    @Override
    public int editComment(long feedbackId, long ownerId, String comment) {
        return jpaRepository.editComment(feedbackId, ownerId, comment, Instant.now());
    }

    @Override
    public int resolve(long feedbackId, long reviewerId, FeedbackResolution resolution, String note, Instant now) {
        // status·closed_at 파생을 여기서 확정해 잘못된 상태 문자열이 CAS 로 흘러가는 것을 막는다.
        String newStatus = (resolution.isTerminal() ? FeedbackStatus.CLOSED : FeedbackStatus.REVIEWING).name();
        Instant closedAt = resolution.isTerminal() ? now : null;
        return jpaRepository.resolve(feedbackId, reviewerId, resolution.value(), note, newStatus, closedAt, now);
    }

    @Override
    public int confirm(
            long feedbackId,
            long reviewerId,
            long executionId,
            Long createdRuleId,
            String expectedResolution,
            Instant now) {
        return jpaRepository.confirm(feedbackId, reviewerId, executionId, createdRuleId, expectedResolution, now);
    }
}
