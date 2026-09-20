package com.npick.search;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.npick.search.application.query.search.VerifyCorrectionCandidatesUseCase;
import com.npick.support.TestGraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

class VerificationRollbackDbTest extends AbstractVerificationSearchDbTest {

    private static final long MEMBER_ID = 8303001L, CLIP_ID = 8303010L, RUN_ID = 8303020L,
            SCENE_ID = 8303030L, EXEC_ID = 8303040L, RESULT_ID = 8303050L, FEEDBACK_ID = 8303060L,
            EVIDENCE_ID = 8303070L;

    @Autowired private VerifyCorrectionCandidatesUseCase useCase;

    @BeforeEach
    void seed() {
        TestGraph.insertSearchableReportedScene(jdbc, MEMBER_ID, CLIP_ID, RUN_ID, SCENE_ID, EXEC_ID, RESULT_ID, FEEDBACK_ID);
        TestGraph.insertReviewerTagCandidate(jdbc, SCENE_ID, CLIP_ID, FEEDBACK_ID, EVIDENCE_ID); // confirmed=false
        // Fix round 1(authz): verify()가 이제 담당 검수자·대기 후보 존재를 검사하므로 함께 심는다.
        TestGraph.claimFeedback(jdbc, FEEDBACK_ID, MEMBER_ID);
    }

    @Test
    @DisplayName("검증이 끝나면 후보 flip 이 롤백돼 공유 데이터가 그대로다")
    void rollsBackCandidateFlip() {
        useCase.verify(FEEDBACK_ID, MEMBER_ID);
        Boolean confirmed = jdbc.queryForObject(
                "SELECT confirmed FROM npick.tag_evidence WHERE evidence_id = ?", Boolean.class, EVIDENCE_ID);
        assertThat(confirmed).isFalse(); // flip(true) 이 롤백됐다
    }

    @Test
    @DisplayName("리졸버 호출은 롤백 트랜잭션 밖에서 일어난다 (S15P21A501-219)")
    void resolverRunsOutsideRollbackTransaction() {
        AtomicBoolean resolverCalled = new AtomicBoolean();
        AtomicBoolean transactionActiveDuringResolve = new AtomicBoolean();
        // doAnswer(...).when(mock) 를 쓴다 — given(resolver.resolve(any())) 는 재스터빙 등록 전에 mock 을
        // 한 번 실제로 호출해 부모의 기존 스텁(정상 해석)을 실행시키는데, 그 answer 는 실 인자를 받는 대신
        // any() 매처의 자리표시자(null)를 받아 tokens(null) 에서 NPE 를 낸다.
        doAnswer(invocation -> {
                    resolverCalled.set(true);
                    transactionActiveDuringResolve.set(TransactionSynchronizationManager.isActualTransactionActive());
                    return resolvedResult(invocation.getArgument(0));
                })
                .when(resolver)
                .resolve(any());

        useCase.verify(FEEDBACK_ID, MEMBER_ID);

        assertThat(resolverCalled).isTrue();
        // interpreter.resolve() 는 rollbackTemplate.execute() 진입 전에 끝나므로, 호출 시점에
        // (검증) 롤백 트랜잭션은 아직 열려 있지 않다 — 리졸버 지연이 flip 이 쥔 행 잠금에 더해지지 않는다.
        assertThat(transactionActiveDuringResolve).isFalse();
    }
}
