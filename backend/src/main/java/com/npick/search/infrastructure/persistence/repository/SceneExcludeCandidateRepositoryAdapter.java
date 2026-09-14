package com.npick.search.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.npick.common.persistence.TsidGenerator;
import com.npick.search.domain.model.SceneExcludeCandidate;
import com.npick.search.domain.repository.SceneExcludeCandidateRepository;

@Repository
public class SceneExcludeCandidateRepositoryAdapter implements SceneExcludeCandidateRepository {

    private final SceneExcludeCandidateJpaRepository jpaRepository;

    public SceneExcludeCandidateRepositoryAdapter(SceneExcludeCandidateJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    // INSERT 를 자기 트랜잭션으로 격리한다. 유니크 위반이 나도 이 트랜잭션만 롤백되고 호출부는 abort 되지 않아 복구 조회가 가능하다.
    @Override
    @Transactional
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
