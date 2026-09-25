package com.npick.search.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.npick.common.error.BusinessException;
import com.npick.common.persistence.CorrectionStateLock;
import com.npick.search.application.error.ParseRuleCandidateErrorCode;
import com.npick.search.application.port.ParseContext;
import com.npick.search.application.port.ParseContextPort;
import com.npick.search.domain.repository.SearchRuleConfirmationRepository;

/**
 * 검수자가 확정 전에 실수로 만든 대기 중인 해석 교정(patch_parse) 후보를 취소한다 (S15P21A501-309, F-11).
 *
 * <p>전제(검수 중·담당 검수자)만 {@link CreateParsePatchCandidateService} 와 같은 방식으로 다시 확인한다. 취소는 대기 후보를 지우는 것이라 생성과 달리
 * 본문(parse-rule/v1)·판정(resolution) 검증은 하지 않는다. patch_parse 후보만 지우므로 같은 신고의 exclude_scene 후보는 남는다.
 *
 * <p>같은 교정 상태 잠금을 잡은 짧은 트랜잭션 안에서 전제를 다시 읽고 후보를 지운다 — 확정(-84)과 동시에 실행돼도 stale 한 상태를 건드리지 않는다.
 */
@Service
public class DiscardParsePatchCandidateService implements DiscardParsePatchCandidateUseCase {

    private final ParseContextPort parseContextPort;
    private final SearchRuleConfirmationRepository confirmationRepository;
    private final CorrectionStateLock correctionStateLock;

    public DiscardParsePatchCandidateService(
            ParseContextPort parseContextPort,
            SearchRuleConfirmationRepository confirmationRepository,
            CorrectionStateLock correctionStateLock) {
        this.parseContextPort = parseContextPort;
        this.confirmationRepository = confirmationRepository;
        this.correctionStateLock = correctionStateLock;
    }

    @Override
    @Transactional
    public int discard(long feedbackId, long reviewerId, boolean reviewerRole) {
        if (!reviewerRole) {
            throw new BusinessException(ParseRuleCandidateErrorCode.EDITOR_FORBIDDEN);
        }
        correctionStateLock.acquire();
        ParseContext context = parseContextPort
                .find(feedbackId)
                .orElseThrow(() -> new BusinessException(ParseRuleCandidateErrorCode.FEEDBACK_NOT_FOUND));
        if (!"REVIEWING".equals(context.status())) {
            throw new BusinessException(ParseRuleCandidateErrorCode.NOT_REVIEWING);
        }
        if (context.reviewedById() == null || context.reviewedById() != reviewerId) {
            throw new BusinessException(ParseRuleCandidateErrorCode.NOT_REVIEWER);
        }
        return confirmationRepository.discardPendingParse(feedbackId);
    }
}
