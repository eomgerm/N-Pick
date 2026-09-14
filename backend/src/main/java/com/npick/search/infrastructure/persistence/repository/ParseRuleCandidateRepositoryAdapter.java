package com.npick.search.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.npick.common.persistence.TsidGenerator;
import com.npick.search.domain.model.ParseRuleCandidate;
import com.npick.search.domain.repository.ParseRuleCandidateRepository;

@Repository
public class ParseRuleCandidateRepositoryAdapter implements ParseRuleCandidateRepository {

    private final ParseRuleCandidateJpaRepository jpaRepository;

    public ParseRuleCandidateRepositoryAdapter(ParseRuleCandidateJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public long save(ParseRuleCandidate candidate) {
        long id = TsidGenerator.generate();
        jpaRepository.insertCandidate(
                id,
                candidate.sourceFeedbackId(),
                candidate.conditionJson(),
                candidate.patchJson(),
                candidate.replacesRuleId(),
                candidate.requestKey(),
                Instant.now());
        return id;
    }

    @Override
    public Optional<Long> findId(long sourceFeedbackId, String requestKey) {
        return jpaRepository.findIds(sourceFeedbackId, requestKey).stream().findFirst();
    }
}
