package com.npick.feedback.application;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.npick.common.error.BusinessException;
import com.npick.feedback.application.error.ConfirmCorrectionErrorCode;
import com.npick.feedback.application.port.ConfirmationTarget;
import com.npick.feedback.application.port.ConfirmationTargetPort;
import com.npick.feedback.application.port.CurrentCorrectionStatePort;
import com.npick.feedback.application.port.VerificationRun;
import com.npick.feedback.application.port.VerificationRunPort;
import com.npick.feedback.domain.model.FeedbackResolution;
import com.npick.feedback.domain.model.FeedbackStatus;
import com.npick.search.application.ConfirmParseRuleUseCase;
import com.npick.tag.application.ConfirmTagCorrectionUseCase;

/**
 * 검수자가 확인한 검증 실행을 근거로 교정을 확정한다 (S15P21A501-84, F-13).
 *
 * <p>전제와 대상 검증, drift 판정을 마치면 태그·규칙 확정과 신고 종료를 <b>한 트랜잭션</b>으로 확정한다. 태그·규칙 쓰기는 각 도메인이 공개한 UseCase 로 위임하고(설계 정본 §14), 세
 * UseCase 가 같은 Spring 트랜잭션을 공유해 원자성을 지킨다.
 */
@Service
public class ConfirmCorrectionService {

    private final ConfirmationTargetPort targetPort;
    private final VerificationRunPort verificationRunPort;
    private final CurrentCorrectionStatePort currentStatePort;
    private final ConfirmTagCorrectionUseCase confirmTag;
    private final ConfirmParseRuleUseCase confirmParseRule;
    private final com.npick.feedback.domain.repository.FeedbackRepository feedbackRepository;

    public ConfirmCorrectionService(
            ConfirmationTargetPort targetPort,
            VerificationRunPort verificationRunPort,
            CurrentCorrectionStatePort currentStatePort,
            ConfirmTagCorrectionUseCase confirmTag,
            ConfirmParseRuleUseCase confirmParseRule,
            com.npick.feedback.domain.repository.FeedbackRepository feedbackRepository) {
        this.targetPort = targetPort;
        this.verificationRunPort = verificationRunPort;
        this.currentStatePort = currentStatePort;
        this.confirmTag = confirmTag;
        this.confirmParseRule = confirmParseRule;
        this.feedbackRepository = feedbackRepository;
    }

    @Transactional
    public void confirm(ConfirmCorrectionCommand command) {
        if (!command.reviewerRole()) {
            throw new BusinessException(ConfirmCorrectionErrorCode.EDITOR_FORBIDDEN);
        }
        ConfirmationTarget target = targetPort
                .find(command.feedbackId())
                .orElseThrow(() -> new BusinessException(ConfirmCorrectionErrorCode.FEEDBACK_NOT_FOUND));

        // 멱등: 같은 검증 실행으로 이미 확정됐으면 재요청은 조용히 성공한다(버튼 재클릭·응답 유실 후 재시도, F-13 완료 기준).
        if (FeedbackStatus.CLOSED.name().equals(target.status())
                && target.verifiedByExecutionId() != null
                && target.verifiedByExecutionId() == command.executionId()) {
            return;
        }
        if (!FeedbackStatus.REVIEWING.name().equals(target.status())) {
            throw new BusinessException(ConfirmCorrectionErrorCode.NOT_REVIEWING);
        }
        boolean isCorrection = FeedbackResolution.TAG_CORRECTION.value().equals(target.resolution())
                || FeedbackResolution.PATCH_PARSE.value().equals(target.resolution());
        if (!isCorrection) {
            throw new BusinessException(ConfirmCorrectionErrorCode.NOT_A_CORRECTION);
        }
        if (target.reviewedById() == null || target.reviewedById() != command.reviewerId()) {
            throw new BusinessException(ConfirmCorrectionErrorCode.NOT_REVIEWER);
        }

        VerificationRun run = verificationRunPort
                .find(command.executionId(), command.feedbackId())
                .orElseThrow(() -> new BusinessException(ConfirmCorrectionErrorCode.NOT_VERIFICATION_RUN));
        if (!run.stateFingerprint().equals(currentStatePort.currentFingerprint(command.feedbackId()))) {
            throw new BusinessException(ConfirmCorrectionErrorCode.NEEDS_REVERIFICATION);
        }

        // patch_parse(태그·해석 모두 잘못, F-09)는 규칙과 태그를 함께 확정한다. tag_correction 은 태그만. 배타 분기가 아니라,
        // 규칙 확정은 patch_parse 일 때, 태그 확정은 검증이 승인한 근거가 있을 때 각각 일어난다.
        if (FeedbackResolution.PATCH_PARSE.value().equals(target.resolution())) {
            confirmParseRule.confirm(command.feedbackId(), run.approvedRuleId(), run.replacedRuleId());
        }
        if (!run.approvedEvidenceIds().isEmpty()) {
            confirmTag.confirm(command.feedbackId(), run.approvedEvidenceIds());
        }
        if (feedbackRepository.confirm(command.feedbackId(), command.reviewerId(), command.executionId(), Instant.now())
                == 0) {
            // 전제 조회와 확정 CAS 사이 경합(다른 확정·종료). 트랜잭션을 롤백해 태그·규칙 확정을 되돌린다.
            throw new BusinessException(ConfirmCorrectionErrorCode.NOT_REVIEWING);
        }
    }
}
