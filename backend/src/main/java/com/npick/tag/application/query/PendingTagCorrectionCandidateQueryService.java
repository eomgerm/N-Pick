package com.npick.tag.application.query;

import java.util.List;

import org.springframework.stereotype.Service;

/** 대기 중인 태그 교정 근거 조회 (S15P21A501-317). 단건 Projection 조회라 트랜잭션을 붙이지 않는다(설계 정본 §5). */
@Service
public class PendingTagCorrectionCandidateQueryService implements ListPendingTagCorrectionCandidatesUseCase {

    private final FindPendingTagCorrectionCandidatesQueryPort queryPort;

    public PendingTagCorrectionCandidateQueryService(FindPendingTagCorrectionCandidatesQueryPort queryPort) {
        this.queryPort = queryPort;
    }

    @Override
    public List<PendingTagCorrectionCandidate> listPending(long sourceFeedbackId) {
        return queryPort.findPending(sourceFeedbackId);
    }
}
