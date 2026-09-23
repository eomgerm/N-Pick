package com.npick.feedback.application;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.npick.common.error.BusinessException;
import com.npick.common.persistence.CorrectionStateLock;
import com.npick.feedback.application.error.ConfirmCorrectionErrorCode;
import com.npick.feedback.application.port.ConfirmationTarget;
import com.npick.feedback.application.port.ConfirmationTargetPort;
import com.npick.feedback.application.port.CurrentCorrectionStatePort;
import com.npick.feedback.application.port.ExcludeTargetValidityPort;
import com.npick.feedback.application.port.VerificationRun;
import com.npick.feedback.application.port.VerificationRunPort;
import com.npick.feedback.domain.model.FeedbackResolution;
import com.npick.feedback.domain.model.FeedbackStatus;
import com.npick.search.application.ConfirmExcludeSceneUseCase;
import com.npick.search.application.ConfirmParseRuleUseCase;
import com.npick.tag.application.ConfirmTagCorrectionUseCase;

/**
 * 검수자가 확인한 검증 실행을 근거로 교정을 확정한다 (S15P21A501-84, F-13).
 *
 * <p>전제와 대상 검증, drift 판정을 마치면 태그·규칙 확정과 신고 종료를 <b>한 트랜잭션</b>으로 확정한다. 태그·규칙 쓰기는 각 도메인이 공개한 UseCase 로 위임하고(설계 정본 §14), 세
 * UseCase 가 같은 Spring 트랜잭션을 공유해 원자성을 지킨다.
 */
@Service
public class ConfirmCorrectionService implements ConfirmCorrectionUseCase {

    private final ConfirmationTargetPort targetPort;
    private final VerificationRunPort verificationRunPort;
    private final CurrentCorrectionStatePort currentStatePort;
    private final ConfirmTagCorrectionUseCase confirmTag;
    private final ConfirmParseRuleUseCase confirmParseRule;
    private final ConfirmExcludeSceneUseCase confirmExcludeScene;
    private final ExcludeTargetValidityPort excludeValidity;
    private final CorrectionStateLock correctionStateLock;
    private final com.npick.feedback.domain.repository.FeedbackRepository feedbackRepository;

    public ConfirmCorrectionService(
            ConfirmationTargetPort targetPort,
            VerificationRunPort verificationRunPort,
            CurrentCorrectionStatePort currentStatePort,
            ConfirmTagCorrectionUseCase confirmTag,
            ConfirmParseRuleUseCase confirmParseRule,
            ConfirmExcludeSceneUseCase confirmExcludeScene,
            ExcludeTargetValidityPort excludeValidity,
            CorrectionStateLock correctionStateLock,
            com.npick.feedback.domain.repository.FeedbackRepository feedbackRepository) {
        this.targetPort = targetPort;
        this.verificationRunPort = verificationRunPort;
        this.currentStatePort = currentStatePort;
        this.confirmTag = confirmTag;
        this.confirmParseRule = confirmParseRule;
        this.confirmExcludeScene = confirmExcludeScene;
        this.excludeValidity = excludeValidity;
        this.correctionStateLock = correctionStateLock;
        this.feedbackRepository = feedbackRepository;
    }

    @Override
    @Transactional
    public void confirm(ConfirmCorrectionCommand command) {
        if (!command.reviewerRole()) {
            throw new BusinessException(ConfirmCorrectionErrorCode.EDITOR_FORBIDDEN);
        }
        // 교정 상태 잠금 — 확정끼리, 그리고 규칙 사용 중단과도 직렬화해 지문 재확인~쓰기 사이에 활성 규칙 집합이 바뀌어도 drift 검사를 우회하지 못하게 한다(F-13).
        correctionStateLock.acquire();
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
        // 교정 판정인지는 정확한 세부 종류가 아니라 "종료 판정이 아닌가"로 가린다 — 신규 단일화 값(correction)과
        // 기존 세 종류(tag_correction/patch_parse/exclude_scene) 를 모두 받아들이면서, no_action·deferred 처럼
        // 그 자체로 종료되는 판정만 배제한다. fromValue() 로 세 종류를 CORRECTION 에 뭉개 비교하지 않는다 — 아래
        // 축별 적용은 이 판정 문자열이 아니라 검증이 승인한 후보의 존재로 갈린다.
        FeedbackResolution parsedResolution = FeedbackResolution.parse(target.resolution());
        if (parsedResolution == null || parsedResolution.isTerminal()) {
            throw new BusinessException(ConfirmCorrectionErrorCode.NOT_A_CORRECTION);
        }
        if (target.reviewedById() == null || target.reviewedById() != command.reviewerId()) {
            throw new BusinessException(ConfirmCorrectionErrorCode.NOT_REVIEWER);
        }

        VerificationRun run = verificationRunPort
                .find(command.executionId(), command.feedbackId())
                .orElseThrow(() -> new BusinessException(ConfirmCorrectionErrorCode.NOT_VERIFICATION_RUN));
        // 검증한 판정과 현재 판정이 다르면(검수 중 PUT 으로 resolution 을 덮어씀) 검증하지 않은 종류를 확정하는 것이라 재검증한다.
        if (!run.resolution().equals(target.resolution())) {
            throw new BusinessException(ConfirmCorrectionErrorCode.NEEDS_REVERIFICATION);
        }
        if (!run.stateFingerprint().equals(currentStatePort.currentFingerprint(command.feedbackId()))) {
            throw new BusinessException(ConfirmCorrectionErrorCode.NEEDS_REVERIFICATION);
        }

        // 한 신고가 태그 근거와 규칙 후보를 함께 들고 있을 수 있다(F-09 혼합 교정) — 배타 분기가 아니라 축마다 대기 후보가
        // "있는가"로 적용 여부를 가른다. 규칙 축은 검증 스냅샷 한 슬롯만 담을 수 있어 exclude_scene/patch_parse 둘 중
        // 어느 쪽인지는 검증이 승인한 run.resolution() 으로 가린다 — target.resolution() 처럼 확정 사이에 바뀔 수 있는
        // 값이 아니라, 이 실행이 검증한 시점에 고정된 스냅샷이라 안전하다(위 run.resolution()·target.resolution() 일치
        // 검사로 이미 같음이 보장된다). 즉 "적용할지"는 후보 존재로, "어느 규칙 유스케이스인지"만 검증 스냅샷의 종류로 정한다.
        boolean hasRuleCandidate = run.approvedRuleId() != null;
        boolean isExcludeScene =
                hasRuleCandidate && FeedbackResolution.EXCLUDE_SCENE.value().equals(run.approvedRuleAction());
        boolean isPatchParse =
                hasRuleCandidate && FeedbackResolution.PATCH_PARSE.value().equals(run.approvedRuleAction());
        boolean hasTagCandidate = !run.approvedEvidenceIds().isEmpty();

        // 장면 제외는 확정 직전에 대상 장면이 여전히 유효한지 다시 확인한다(F-14). 검증과 확정 사이에 재처리가 끼면 대상 장면이
        // 사라지므로, 쓰기 전에 막아 신고를 reviewing 으로 남긴다. drift(규칙·근거 변경)와 구분되는 제외 고유 게이트다.
        if (isExcludeScene && !excludeValidity.targetSceneActive(run.approvedRuleId())) {
            throw new BusinessException(ConfirmCorrectionErrorCode.TARGET_SCENE_GONE);
        }

        // 실제 적용 행 수가 승인 대상 수와 다르면 후보가 그대로 적용되지 않은 것이라 확정을 막는다(F-12). 검증 이후 근거·규칙이 사라진 경우다.
        if (isPatchParse
                && confirmParseRule.confirm(command.feedbackId(), run.approvedRuleId(), run.replacedRuleId()) != 1) {
            throw new BusinessException(ConfirmCorrectionErrorCode.NEEDS_REVERIFICATION);
        }
        if (isExcludeScene && confirmExcludeScene.confirm(command.feedbackId(), run.approvedRuleId()) != 1) {
            throw new BusinessException(ConfirmCorrectionErrorCode.NEEDS_REVERIFICATION);
        }
        if (hasTagCandidate
                && confirmTag.confirm(command.feedbackId(), run.approvedEvidenceIds())
                        != run.approvedEvidenceIds().size()) {
            throw new BusinessException(ConfirmCorrectionErrorCode.NEEDS_REVERIFICATION);
        }
        // 최종 승인한 교정 규칙을 신고에 기록한다. 규칙 후보가 있으면 그 id, 태그만 교정하면 NULL 이다(F-13·baseline 주석).
        // expectedResolution 은 CAS WHERE 조건이라 지금 저장된 값(target.resolution())을 그대로 넘겨야 매칭된다 —
        // "correction" 을 넘기면 옛 세부 종류 값과 절대 일치하지 않아 모든 확정이 0행으로 막힌다.
        // newResolution 은 세 세부 종류를 단일화한 correction 값으로 실제로 SET 될 값이다(F-09 재설계) — 검수자는
        // 더 이상 하위 종류를 직접 고르지 않고, 실제로 무엇이 적용됐는지는 created_rule_id·tag_evidence 확정 여부로 드러난다.
        if (feedbackRepository.confirm(
                        command.feedbackId(),
                        command.reviewerId(),
                        command.executionId(),
                        run.approvedRuleId(),
                        target.resolution(),
                        FeedbackResolution.CORRECTION.value(),
                        Instant.now())
                == 0) {
            // 전제 조회와 확정 CAS 사이 경합(다른 확정·종료). 트랜잭션을 롤백해 태그·규칙 확정을 되돌린다.
            throw new BusinessException(ConfirmCorrectionErrorCode.NOT_REVIEWING);
        }
    }
}
