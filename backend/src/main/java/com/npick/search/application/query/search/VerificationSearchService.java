package com.npick.search.application.query.search;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 후보 검증 자동 재검색 오케스트레이션 (S15P21A501-83, FRD F-12·§11).
 *
 * <p>같은 패키지에 두는 이유: {@link SearchRecordPayload}·{@link SearchExplain} 이 package-private 이고, 검증 검색은
 * 일반 검색과 <b>같은 기록 형태</b>를 남겨야 하므로(FRD §11) 그 변환을 재사용해야 한다.
 */
@Service
public class VerificationSearchService implements VerifyCorrectionCandidatesUseCase {

    private final VerificationInputPort inputPort;
    private final PendingCandidatesPort candidatesPort;
    private final InterpretSearchQueryUseCase interpreter;
    private final RankSearchCandidatesUseCase ranker;
    private final TransactionTemplate rollbackTemplate;
    private final EntityManager em;

    public VerificationSearchService(
            VerificationInputPort inputPort,
            PendingCandidatesPort candidatesPort,
            InterpretSearchQueryUseCase interpreter,
            RankSearchCandidatesUseCase ranker,
            PlatformTransactionManager txManager,
            EntityManager em) {
        this.inputPort = inputPort;
        this.candidatesPort = candidatesPort;
        this.interpreter = interpreter;
        this.ranker = ranker;
        this.em = em;
        this.rollbackTemplate = new TransactionTemplate(txManager);
        this.rollbackTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public VerificationResult verify(long feedbackId, long reviewerId) {
        VerificationInput input = inputPort.load(feedbackId);
        PendingCandidates candidates = candidatesPort.load(feedbackId);
        ExecuteSearchQuery query = new ExecuteSearchQuery(input.rawQuery(), input.dateFilters(), reviewerId);
        // Task 3: flip → 같은 코드로 재검색 → 캡처 → 롤백. 기록·diff 는 Task 4.
        searchWithCandidatesRolledBack(query, candidates);
        return new VerificationResult(0L); // Task 4 가 실제 executionId 로 채운다
    }

    /**
     * 롤백 트랜잭션 안에서 검색 결과와 해석을 함께 캡처한다. 검색은 롤백되지만 이 값은 트랜잭션 밖으로
     * 나가 replay 기록에 쓰인다 — 일반 검색 기록과 같은 충실도(finalResolution·degradedReasons·
     * rules().outcomes())를 남기기 위해 {@code SearchCandidates} 만이 아니라 {@code InterpretedQuery} 도 담는다.
     */
    record VerificationSearchOutcome(SearchCandidates candidates, InterpretedQuery interpreted) {}

    /** flip → 같은 코드로 재검색 → 캡처 → 롤백. 공유·확정 데이터는 복구된다 (FRD §11). */
    private VerificationSearchOutcome searchWithCandidatesRolledBack(
            ExecuteSearchQuery query, PendingCandidates candidates) {
        return rollbackTemplate.execute(status -> {
            flip(candidates);
            InterpretedQuery iq = interpreter.interpret(query);
            SearchCandidates result = ranker.rank(new RankSearchCandidatesUseCase.Query(
                    iq.resolved().normalization(), iq.finalResolution(),
                    iq.resolved().queryEmbedding(), iq.normalizedSearch()));
            status.setRollbackOnly(); // flip 과 후보 적용을 모두 되돌린다
            return new VerificationSearchOutcome(result, iq);
        });
    }

    private void flip(PendingCandidates c) {
        if (!c.tagEvidenceIds().isEmpty()) {
            em.createNativeQuery("UPDATE npick.tag_evidence SET confirmed = true WHERE evidence_id IN (:ids)")
                    .setParameter("ids", c.tagEvidenceIds())
                    .executeUpdate();
        }
        if (c.approvedRuleId() != null) {
            em.createNativeQuery("UPDATE npick.search_rule SET active = true WHERE search_rule_id = :r2")
                    .setParameter("r2", c.approvedRuleId()).executeUpdate();
        }
        if (c.replacedRuleId() != null) {
            em.createNativeQuery("UPDATE npick.search_rule SET active = false WHERE search_rule_id = :r1")
                    .setParameter("r1", c.replacedRuleId()).executeUpdate();
        }
    }
}
