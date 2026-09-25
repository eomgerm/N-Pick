package com.npick.tag.application;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.common.error.BusinessException;
import com.npick.common.persistence.CorrectionStateLock;
import com.npick.tag.application.error.TagCorrectionCandidateErrorCode;
import com.npick.tag.application.port.TagContext;
import com.npick.tag.application.port.TagContextPort;
import com.npick.tag.domain.repository.TagCorrectionConfirmationRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiscardTagCorrectionCandidateServiceTest {

    private TagContextPort tagContextPort;
    private TagCorrectionConfirmationRepository confirmationRepository;
    private CorrectionStateLock correctionStateLock;
    private DiscardTagCorrectionCandidateService service;

    @BeforeEach
    void setUp() {
        tagContextPort = mock(TagContextPort.class);
        confirmationRepository = mock(TagCorrectionConfirmationRepository.class);
        correctionStateLock = mock(CorrectionStateLock.class);
        service = new DiscardTagCorrectionCandidateService(
                tagContextPort, confirmationRepository, correctionStateLock);
    }

    private void reviewingCorrection() {
        when(tagContextPort.find(1L)).thenReturn(Optional.of(new TagContext("REVIEWING", "correction", 9L, 300L, 100L)));
    }

    @Test
    @DisplayName("전제가 맞으면 대기 근거를 지우고 지운 수를 준다")
    void discardsPendingCandidate() {
        reviewingCorrection();
        when(confirmationRepository.discardPending(1L)).thenReturn(2);

        int discarded = service.discard(1L, 9L, true);

        verify(correctionStateLock).acquire();
        assertThat(discarded).isEqualTo(2);
        verify(confirmationRepository).discardPending(1L);
    }

    @Test
    @DisplayName("편집기자면 거부한다")
    void rejectsEditor() {
        assertThatThrownBy(() -> service.discard(1L, 9L, false))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(TagCorrectionCandidateErrorCode.EDITOR_FORBIDDEN));
        verify(confirmationRepository, never()).discardPending(1L);
        verify(correctionStateLock, never()).acquire();
    }

    @Test
    @DisplayName("없는 신고면 거부한다")
    void rejectsMissingFeedback() {
        when(tagContextPort.find(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.discard(1L, 9L, true))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(TagCorrectionCandidateErrorCode.FEEDBACK_NOT_FOUND));
    }

    @Test
    @DisplayName("검수 중이 아니면 거부한다")
    void rejectsNotReviewing() {
        when(tagContextPort.find(1L)).thenReturn(Optional.of(new TagContext("CLOSED", "correction", 9L, 300L, 100L)));
        assertThatThrownBy(() -> service.discard(1L, 9L, true))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(TagCorrectionCandidateErrorCode.NOT_REVIEWING));
        verify(confirmationRepository, never()).discardPending(1L);
    }

    @Test
    @DisplayName("담당 검수자가 아니면 거부한다")
    void rejectsNonOwnerReviewer() {
        reviewingCorrection();
        assertThatThrownBy(() -> service.discard(1L, 7L, true))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(TagCorrectionCandidateErrorCode.NOT_REVIEWER));
        verify(confirmationRepository, never()).discardPending(1L);
    }

    @Test
    @DisplayName("개별 취소는 전제가 맞으면 지정한 근거 하나만 지운다")
    void discardsOnePendingCandidate() {
        reviewingCorrection();
        when(confirmationRepository.discardPendingOne(1L, 5001L)).thenReturn(1);

        int discarded = service.discardOne(1L, 5001L, 9L, true);

        verify(correctionStateLock).acquire();
        assertThat(discarded).isEqualTo(1);
        verify(confirmationRepository).discardPendingOne(1L, 5001L);
        verify(confirmationRepository, never()).discardPending(1L);
    }

    @Test
    @DisplayName("개별 취소도 대상 근거가 없으면 0건으로 조용히 끝난다 (멱등)")
    void discardOneIsIdempotent() {
        reviewingCorrection();
        when(confirmationRepository.discardPendingOne(1L, 5001L)).thenReturn(0);

        assertThat(service.discardOne(1L, 5001L, 9L, true)).isEqualTo(0);
    }

    @Test
    @DisplayName("개별 취소도 편집기자면 잠금 전에 거부한다")
    void discardOneRejectsEditor() {
        assertThatThrownBy(() -> service.discardOne(1L, 5001L, 9L, false))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(TagCorrectionCandidateErrorCode.EDITOR_FORBIDDEN));
        verify(correctionStateLock, never()).acquire();
        verify(confirmationRepository, never()).discardPendingOne(1L, 5001L);
    }

    @Test
    @DisplayName("개별 취소도 없는 신고면 거부한다")
    void discardOneRejectsMissingFeedback() {
        when(tagContextPort.find(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.discardOne(1L, 5001L, 9L, true))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(TagCorrectionCandidateErrorCode.FEEDBACK_NOT_FOUND));
        verify(confirmationRepository, never()).discardPendingOne(1L, 5001L);
    }

    @Test
    @DisplayName("개별 취소도 검수 중이 아니면 거부한다")
    void discardOneRejectsNotReviewing() {
        when(tagContextPort.find(1L)).thenReturn(Optional.of(new TagContext("CLOSED", "correction", 9L, 300L, 100L)));
        assertThatThrownBy(() -> service.discardOne(1L, 5001L, 9L, true))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(TagCorrectionCandidateErrorCode.NOT_REVIEWING));
        verify(confirmationRepository, never()).discardPendingOne(1L, 5001L);
    }

    @Test
    @DisplayName("개별 취소도 담당 검수자가 아니면 거부한다")
    void discardOneRejectsNonOwnerReviewer() {
        reviewingCorrection();
        assertThatThrownBy(() -> service.discardOne(1L, 5001L, 7L, true))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(TagCorrectionCandidateErrorCode.NOT_REVIEWER));
        verify(confirmationRepository, never()).discardPendingOne(1L, 5001L);
    }
}
