package com.npick.search.application;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.common.error.BusinessException;
import com.npick.common.persistence.CorrectionStateLock;
import com.npick.search.application.error.ParseRuleCandidateErrorCode;
import com.npick.search.application.port.ParseContext;
import com.npick.search.application.port.ParseContextPort;
import com.npick.search.domain.repository.SearchRuleConfirmationRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiscardParsePatchCandidateServiceTest {

    private ParseContextPort parseContextPort;
    private SearchRuleConfirmationRepository confirmationRepository;
    private CorrectionStateLock correctionStateLock;
    private DiscardParsePatchCandidateService service;

    @BeforeEach
    void setUp() {
        parseContextPort = mock(ParseContextPort.class);
        confirmationRepository = mock(SearchRuleConfirmationRepository.class);
        correctionStateLock = mock(CorrectionStateLock.class);
        service = new DiscardParsePatchCandidateService(
                parseContextPort, confirmationRepository, correctionStateLock);
    }

    private void reviewingCorrection() {
        when(parseContextPort.find(1L))
                .thenReturn(Optional.of(new ParseContext("REVIEWING", "correction", 9L, "{}")));
    }

    @Test
    @DisplayName("전제가 맞으면 대기 patch_parse 후보를 지우고 지운 수를 준다")
    void discardsPendingCandidate() {
        reviewingCorrection();
        when(confirmationRepository.discardPendingParse(1L)).thenReturn(1);

        int discarded = service.discard(1L, 9L, true);

        verify(correctionStateLock).acquire();
        assertThat(discarded).isEqualTo(1);
        verify(confirmationRepository).discardPendingParse(1L);
    }

    @Test
    @DisplayName("편집기자면 거부한다")
    void rejectsEditor() {
        assertThatThrownBy(() -> service.discard(1L, 9L, false))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ParseRuleCandidateErrorCode.EDITOR_FORBIDDEN));
        verify(confirmationRepository, never()).discardPendingParse(1L);
        verify(correctionStateLock, never()).acquire();
    }

    @Test
    @DisplayName("없는 신고면 거부한다")
    void rejectsMissingFeedback() {
        when(parseContextPort.find(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.discard(1L, 9L, true))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ParseRuleCandidateErrorCode.FEEDBACK_NOT_FOUND));
    }

    @Test
    @DisplayName("검수 중이 아니면 거부한다")
    void rejectsNotReviewing() {
        when(parseContextPort.find(1L)).thenReturn(Optional.of(new ParseContext("CLOSED", "correction", 9L, "{}")));
        assertThatThrownBy(() -> service.discard(1L, 9L, true))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ParseRuleCandidateErrorCode.NOT_REVIEWING));
        verify(confirmationRepository, never()).discardPendingParse(1L);
    }

    @Test
    @DisplayName("담당 검수자가 아니면 거부한다")
    void rejectsNonOwnerReviewer() {
        reviewingCorrection();
        assertThatThrownBy(() -> service.discard(1L, 7L, true))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ParseRuleCandidateErrorCode.NOT_REVIEWER));
        verify(confirmationRepository, never()).discardPendingParse(1L);
    }
}
