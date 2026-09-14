package com.npick.search.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.npick.common.persistence.TsidGenerator;
import com.npick.search.domain.model.ParseRuleCandidate;
import com.npick.search.domain.repository.ParseRuleCandidateRepository;

@Repository
public class ParseRuleCandidateRepositoryAdapter implements ParseRuleCandidateRepository {

    private final ParseRuleCandidateJpaRepository jpaRepository;

    public ParseRuleCandidateRepositoryAdapter(ParseRuleCandidateJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    // INSERT 를 자기 트랜잭션으로 격리한다. 유니크 위반이 나도 이 트랜잭션만 롤백되고 호출부는 abort 되지 않아 복구 조회가 가능하다.
    @Override
    @Transactional
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

    @Override
    public boolean existsActivePatchParse(long searchRuleId) {
        return jpaRepository.existsActivePatchParse(searchRuleId);
    }
}
