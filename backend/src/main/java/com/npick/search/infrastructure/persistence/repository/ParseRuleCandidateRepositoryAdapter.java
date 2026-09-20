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

    // ON CONFLICT DO NOTHING 이라 유니크 위반이 예외로 터지지 않는다. 삽입되면 1, 충돌이면 0 이 온다.
    // @Modifying 은 트랜잭션을 요구하며, 후보 생성 유스케이스가 잠금 획득부터 충돌 복구 조회까지의 경계를 소유한다.
    @Override
    public Optional<Long> insertIfAbsent(ParseRuleCandidate candidate) {
        long id = TsidGenerator.generate();
        int inserted = jpaRepository.insertCandidate(
                id,
                candidate.sourceFeedbackId(),
                candidate.conditionJson(),
                candidate.patchJson(),
                candidate.replacesRuleId(),
                candidate.requestKey(),
                Instant.now());
        return inserted == 1 ? Optional.of(id) : Optional.empty();
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
