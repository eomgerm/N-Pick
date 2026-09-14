package com.npick.search.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.npick.common.persistence.TsidGenerator;
import com.npick.search.domain.model.SceneExcludeCandidate;
import com.npick.search.domain.repository.SceneExcludeCandidateRepository;

@Repository
public class SceneExcludeCandidateRepositoryAdapter implements SceneExcludeCandidateRepository {

    private final SceneExcludeCandidateJpaRepository jpaRepository;

    public SceneExcludeCandidateRepositoryAdapter(SceneExcludeCandidateJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public long save(SceneExcludeCandidate candidate) {
        long id = TsidGenerator.generate();
        jpaRepository.insertCandidate(
                id,
                candidate.queryFingerprint(),
                candidate.normalizedQuery(),
                candidate.normalizedFiltersJson(),
                candidate.normalizationVersion(),
                candidate.targetSceneId(),
                candidate.sourceFeedbackId(),
                candidate.requestKey(),
                Instant.now());
        return id;
    }

    @Override
    public Optional<Long> findId(long sourceFeedbackId, String requestKey) {
        return jpaRepository.findIds(sourceFeedbackId, requestKey).stream().findFirst();
    }
}
