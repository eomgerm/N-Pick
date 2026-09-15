package com.npick.feedback.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.common.error.BusinessException;
import com.npick.feedback.application.error.ConfirmCorrectionErrorCode;
import com.npick.feedback.application.port.ConfirmationTarget;
import com.npick.feedback.application.port.ConfirmationTargetPort;
import com.npick.feedback.application.port.CurrentCorrectionStatePort;
import com.npick.feedback.application.port.VerificationRun;
import com.npick.feedback.application.port.VerificationRunPort;
import com.npick.feedback.domain.repository.FeedbackRepository;
import com.npick.search.application.ConfirmParseRuleUseCase;
import com.npick.tag.application.ConfirmTagCorrectionUseCase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConfirmCorrectionServiceTest {

    private static final long FEEDBACK = 9901L;
    private static final long REVIEWER = 9L;
    private static final long EXECUTION = 9701L;
    private static final String FINGERPRINT = "fp-match";

    private ConfirmationTargetPort targetPort;
    private VerificationRunPort verificationRunPort;
    private CurrentCorrectionStatePort currentStatePort;
    private ConfirmTagCorrectionUseCase confirmTag;
    private ConfirmParseRuleUseCase confirmParseRule;
    private FeedbackRepository feedbackRepository;
    private ConfirmCorrectionService service;

    @BeforeEach
    void setUp() {
        targetPort = mock(ConfirmationTargetPort.class);
        verificationRunPort = mock(VerificationRunPort.class);
        currentStatePort = mock(CurrentCorrectionStatePort.class);
        confirmTag = mock(ConfirmTagCorrectionUseCase.class);
        confirmParseRule = mock(ConfirmParseRuleUseCase.class);
        feedbackRepository = mock(FeedbackRepository.class);
        service = new ConfirmCorrectionService(
                targetPort, verificationRunPort, currentStatePort, confirmTag, confirmParseRule, feedbackRepository);
    }

    private ConfirmCorrectionCommand command(boolean reviewerRole) {
        return new ConfirmCorrectionCommand(FEEDBACK, REVIEWER, reviewerRole, EXECUTION);
    }

    private void target(String status, String resolution, Long verifiedBy) {
        when(targetPort.find(FEEDBACK))
                .thenReturn(Optional.of(new ConfirmationTarget(status, resolution, REVIEWER, verifiedBy)));
    }

    private void verificationRun(String resolution, List<Long> evidenceIds, Long ruleId, Long replacedId) {
        when(verificationRunPort.find(EXECUTION, FEEDBACK))
                .thenReturn(Optional.of(
                        new VerificationRun(EXECUTION, resolution, evidenceIds, ruleId, replacedId, FINGERPRINT)));
        when(currentStatePort.currentFingerprint(FEEDBACK)).thenReturn(FINGERPRINT);
    }

    @Test
    @DisplayName("편집기자는 확정할 수 없다")
    void editorForbidden() {
        assertThatThrownBy(() -> service.confirm(command(false)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ConfirmCorrectionErrorCode.EDITOR_FORBIDDEN);
        verifyNoInteractions(feedbackRepository);
    }

    @Test
    @DisplayName("대상 신고가 없으면 실패한다")
    void feedbackNotFound() {
        when(targetPort.find(FEEDBACK)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm(command(true)))
                .extracting("errorCode")
                .isEqualTo(ConfirmCorrectionErrorCode.FEEDBACK_NOT_FOUND);
    }

    @Test
    @DisplayName("검수 중이 아니면 확정할 수 없다")
    void notReviewing() {
        target("OPEN", "tag_correction", null);

        assertThatThrownBy(() -> service.confirm(command(true)))
                .extracting("errorCode")
                .isEqualTo(ConfirmCorrectionErrorCode.NOT_REVIEWING);
    }

    @Test
    @DisplayName("장면 제외·무처리 등 태그·해석 교정이 아니면 확정할 수 없다")
    void notACorrection() {
        target("REVIEWING", "exclude_scene", null);

        assertThatThrownBy(() -> service.confirm(command(true)))
                .extracting("errorCode")
                .isEqualTo(ConfirmCorrectionErrorCode.NOT_A_CORRECTION);
    }

    @Test
    @DisplayName("담당 검수자가 아니면 확정할 수 없다")
    void notReviewer() {
        when(targetPort.find(FEEDBACK))
                .thenReturn(Optional.of(new ConfirmationTarget("REVIEWING", "tag_correction", 999L, null)));

        assertThatThrownBy(() -> service.confirm(command(true)))
                .extracting("errorCode")
                .isEqualTo(ConfirmCorrectionErrorCode.NOT_REVIEWER);
    }

    @Test
    @DisplayName("지정한 검증 실행이 이 신고의 성공한 replay 가 아니면 실패한다")
    void notVerificationRun() {
        target("REVIEWING", "tag_correction", null);
        when(verificationRunPort.find(EXECUTION, FEEDBACK)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm(command(true)))
                .extracting("errorCode")
                .isEqualTo(ConfirmCorrectionErrorCode.NOT_VERIFICATION_RUN);
    }

    @Test
    @DisplayName("검증 이후 상태가 바뀌었으면 재검증을 요구한다")
    void needsReverification() {
        target("REVIEWING", "tag_correction", null);
        verificationRun("tag_correction", List.of(7901L), null, null);
        when(currentStatePort.currentFingerprint(FEEDBACK)).thenReturn("fp-changed");

        assertThatThrownBy(() -> service.confirm(command(true)))
                .extracting("errorCode")
                .isEqualTo(ConfirmCorrectionErrorCode.NEEDS_REVERIFICATION);
        verify(confirmTag, never()).confirm(anyLong(), any());
    }

    @Test
    @DisplayName("태그 교정을 확정하면 근거를 확정하고 신고를 종료한다")
    void confirmsTagCorrection() {
        target("REVIEWING", "tag_correction", null);
        verificationRun("tag_correction", List.of(7901L, 7902L), null, null);
        when(feedbackRepository.confirm(eq(FEEDBACK), eq(REVIEWER), eq(EXECUTION), any())).thenReturn(1);

        service.confirm(command(true));

        verify(confirmTag).confirm(FEEDBACK, List.of(7901L, 7902L));
        verify(confirmParseRule, never()).confirm(anyLong(), anyLong(), any());
        verify(feedbackRepository).confirm(eq(FEEDBACK), eq(REVIEWER), eq(EXECUTION), any(Instant.class));
    }

    @Test
    @DisplayName("해석 교정을 확정하면 규칙을 확정하고 신고를 종료한다")
    void confirmsParseRule() {
        target("REVIEWING", "patch_parse", null);
        verificationRun("patch_parse", List.of(), 6602L, 6601L);
        when(feedbackRepository.confirm(eq(FEEDBACK), eq(REVIEWER), eq(EXECUTION), any())).thenReturn(1);

        service.confirm(command(true));

        verify(confirmParseRule).confirm(FEEDBACK, 6602L, 6601L);
        verify(confirmTag, never()).confirm(anyLong(), any());
        verify(feedbackRepository).confirm(eq(FEEDBACK), eq(REVIEWER), eq(EXECUTION), any(Instant.class));
    }

    @Test
    @DisplayName("patch_parse(태그·해석 모두 잘못)는 규칙과 태그를 함께 확정한다 (F-09)")
    void confirmsParseRuleWithTagCorrection() {
        target("REVIEWING", "patch_parse", null);
        verificationRun("patch_parse", List.of(7901L, 7902L), 6602L, 6601L);
        when(feedbackRepository.confirm(eq(FEEDBACK), eq(REVIEWER), eq(EXECUTION), any())).thenReturn(1);

        service.confirm(command(true));

        verify(confirmParseRule).confirm(FEEDBACK, 6602L, 6601L);
        verify(confirmTag).confirm(FEEDBACK, List.of(7901L, 7902L));
        verify(feedbackRepository).confirm(eq(FEEDBACK), eq(REVIEWER), eq(EXECUTION), any(Instant.class));
    }

    @Test
    @DisplayName("이미 같은 검증 실행으로 확정된 신고는 다시 쓰지 않고 성공한다 (멱등)")
    void idempotentWhenAlreadyConfirmed() {
        target("CLOSED", "tag_correction", EXECUTION);

        service.confirm(command(true));

        verify(confirmTag, never()).confirm(anyLong(), any());
        verify(confirmParseRule, never()).confirm(anyLong(), anyLong(), any());
        verify(feedbackRepository, never()).confirm(anyLong(), anyLong(), anyLong(), any());
    }
}
