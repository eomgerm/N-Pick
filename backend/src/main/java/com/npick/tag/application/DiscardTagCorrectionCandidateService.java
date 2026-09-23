package com.npick.tag.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.npick.common.error.BusinessException;
import com.npick.common.persistence.CorrectionStateLock;
import com.npick.tag.application.error.TagCorrectionCandidateErrorCode;
import com.npick.tag.application.port.TagContext;
import com.npick.tag.application.port.TagContextPort;
import com.npick.tag.domain.repository.TagCorrectionConfirmationRepository;

/**
 * 검수자가 확정 전에 실수로 만든 대기 중인 태그 교정 후보를 취소한다 (S15P21A501-309, F-10).
 *
 * <p>전제(검수 중·담당 검수자)만 {@link CreateTagCorrectionCandidateService} 와 같은 방식으로 다시 확인한다. 취소는 대기 근거 전체를 지우는 것이라 생성과 달리
 * 판정(resolution)·본문 검증은 하지 않는다.
 *
 * <p>같은 교정 상태 잠금을 잡은 짧은 트랜잭션 안에서 전제를 다시 읽고 후보를 지운다 — 확정(-84)과 동시에 실행돼도 stale 한 상태를 건드리지 않는다.
 */
@Service
public class DiscardTagCorrectionCandidateService implements DiscardTagCorrectionCandidateUseCase {

    private final TagContextPort tagContextPort;
    private final TagCorrectionConfirmationRepository confirmationRepository;
    private final CorrectionStateLock correctionStateLock;

    public DiscardTagCorrectionCandidateService(
            TagContextPort tagContextPort,
            TagCorrectionConfirmationRepository confirmationRepository,
            CorrectionStateLock correctionStateLock) {
        this.tagContextPort = tagContextPort;
        this.confirmationRepository = confirmationRepository;
        this.correctionStateLock = correctionStateLock;
    }

    @Override
    @Transactional
    public int discard(long feedbackId, long reviewerId, boolean reviewerRole) {
        if (!reviewerRole) {
            throw new BusinessException(TagCorrectionCandidateErrorCode.EDITOR_FORBIDDEN);
        }
        correctionStateLock.acquire();
        TagContext context = tagContextPort
                .find(feedbackId)
                .orElseThrow(() -> new BusinessException(TagCorrectionCandidateErrorCode.FEEDBACK_NOT_FOUND));
        if (!"REVIEWING".equals(context.status())) {
            throw new BusinessException(TagCorrectionCandidateErrorCode.NOT_REVIEWING);
        }
        if (context.reviewedById() == null || context.reviewedById() != reviewerId) {
            throw new BusinessException(TagCorrectionCandidateErrorCode.NOT_REVIEWER);
        }
        return confirmationRepository.discardPending(feedbackId);
    }
}
