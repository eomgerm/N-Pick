package com.npick.search.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.npick.common.error.BusinessException;
import com.npick.common.persistence.CorrectionStateLock;
import com.npick.search.application.error.SceneExcludeCandidateErrorCode;
import com.npick.search.application.port.ExcludeContext;
import com.npick.search.application.port.ExcludeContextPort;
import com.npick.search.domain.repository.SceneExcludeCandidateRepository;

/**
 * 검수자가 확정 전에 실수로 만든 대기 중인 장면 제외 후보를 취소한다 (S15P21A501-281, F-11).
 *
 * <p>전제(검수 중·담당 검수자)만 {@link CreateSceneExcludeCandidateService} 와 같은 방식으로 다시 확인한다. 취소는 후보 전체를 지우는 것이라 생성과 달리
 * 대상 장면·판정(resolution) 검증은 하지 않는다.
 *
 * <p>같은 교정 상태 잠금을 잡은 짧은 트랜잭션 안에서 전제를 다시 읽고 후보를 지운다 — 확정(-85)과 동시에 실행돼도 stale 한 상태를 건드리지 않는다.
 */
@Service
public class DiscardSceneExcludeCandidateService implements DiscardSceneExcludeCandidateUseCase {

    private final ExcludeContextPort excludeContextPort;
    private final SceneExcludeCandidateRepository candidateRepository;
    private final CorrectionStateLock correctionStateLock;

    public DiscardSceneExcludeCandidateService(
            ExcludeContextPort excludeContextPort,
            SceneExcludeCandidateRepository candidateRepository,
            CorrectionStateLock correctionStateLock) {
        this.excludeContextPort = excludeContextPort;
        this.candidateRepository = candidateRepository;
        this.correctionStateLock = correctionStateLock;
    }

    @Override
    @Transactional
    public int discard(long feedbackId, long reviewerId, boolean reviewerRole) {
        if (!reviewerRole) {
            throw new BusinessException(SceneExcludeCandidateErrorCode.EDITOR_FORBIDDEN);
        }
        correctionStateLock.acquire();
        ExcludeContext context = excludeContextPort
                .find(feedbackId)
                .orElseThrow(() -> new BusinessException(SceneExcludeCandidateErrorCode.FEEDBACK_NOT_FOUND));
        if (!"REVIEWING".equals(context.status())) {
            throw new BusinessException(SceneExcludeCandidateErrorCode.NOT_REVIEWING);
        }
        if (context.reviewedById() == null || context.reviewedById() != reviewerId) {
            throw new BusinessException(SceneExcludeCandidateErrorCode.NOT_REVIEWER);
        }
        return candidateRepository.discardByFeedback(feedbackId);
    }
}
