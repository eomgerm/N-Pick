package com.npick.search.application.query.search;

import org.springframework.stereotype.Service;

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

    public VerificationSearchService(VerificationInputPort inputPort, PendingCandidatesPort candidatesPort) {
        this.inputPort = inputPort;
        this.candidatesPort = candidatesPort;
    }

    @Override
    public VerificationResult verify(long feedbackId, long reviewerId) {
        VerificationInput input = inputPort.load(feedbackId);
        PendingCandidates candidates = candidatesPort.load(feedbackId);
        // Task 3: 기준 지문 → start(replay) → 롤백 트랜잭션(flip+재검색) → complete → diff.
        throw new UnsupportedOperationException("Task 3 에서 채운다");
    }
}
