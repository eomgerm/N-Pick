package com.npick.search.application.query.search;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import com.npick.common.error.BusinessException;
import com.npick.common.persistence.CorrectionStateFingerprint;
import com.npick.search.application.error.VerificationErrorCode;
import com.npick.search.application.port.ExcludeContext;
import com.npick.search.application.port.ExcludeContextPort;
import com.npick.search.application.port.SearchExecutionRecordPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code VerificationSearchService.verify} 의 인가/전제 검사 (fix round 1, S15P21A501-83).
 *
 * <p>{@code CreateSceneExcludeCandidateServiceTest} 와 같은 패턴: 실 DB 없이 포트를 모두 mock 해 가드 순서·오류 코드만
 * 검증한다. 검증은 {@link ExcludeContextPort}(형제 {@code CreateSceneExcludeCandidateService}가 쓰는 바로 그 포트)를 재사용해
 * 신고 존재·상태·담당 검수자를 확인하고, {@link PendingCandidatesPort}로 대기 후보 존재를 확인한다. 이 네 가드 중 하나라도
 * 걸리면 {@code inputPort.load}·{@code record.start} 등 뒤 단계는 전혀 호출되지 않는다.
 */
class VerificationSearchServiceTest {

    private static final long FEEDBACK_ID = 1L;

    private ExcludeContextPort excludeContextPort;
    private VerificationInputPort inputPort;
    private PendingCandidatesPort candidatesPort;
    private SearchExecutionRecordPort record;
    private VerificationSearchService service;

    @BeforeEach
    void setUp() {
        excludeContextPort = mock(ExcludeContextPort.class);
        inputPort = mock(VerificationInputPort.class);
        candidatesPort = mock(PendingCandidatesPort.class);
        InterpretSearchQueryUseCase interpreter = mock(InterpretSearchQueryUseCase.class);
        RankSearchCandidatesUseCase ranker = mock(RankSearchCandidatesUseCase.class);
        record = mock(SearchExecutionRecordPort.class);
        CorrectionStateFingerprint fingerprint = mock(CorrectionStateFingerprint.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        VerificationCandidateStatePort candidateState = mock(VerificationCandidateStatePort.class);
        service = new VerificationSearchService(
                excludeContextPort, inputPort, candidatesPort, interpreter, ranker, record, fingerprint, txManager,
                candidateState);
    }

    private ExcludeContext reviewingContext(Long reviewedById) {
        return new ExcludeContext("REVIEWING", "tag_correction", reviewedById, 300L, "fp-1", "q", "{}", "v1");
    }

    @Test
    @DisplayName("없는 신고면 404 로 거부한다")
    void rejectsMissingFeedback() {
        when(excludeContextPort.find(FEEDBACK_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify(FEEDBACK_ID, 9L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(VerificationErrorCode.FEEDBACK_NOT_FOUND));
        verify(inputPort, never()).load(FEEDBACK_ID);
        verify(record, never()).start(any());
    }

    @Test
    @DisplayName("검수 중이 아니면 409 로 거부한다")
    void rejectsNotReviewing() {
        when(excludeContextPort.find(FEEDBACK_ID))
                .thenReturn(Optional.of(
                        new ExcludeContext("CLOSED", "tag_correction", 9L, 300L, "fp-1", "q", "{}", "v1")));

        assertThatThrownBy(() -> service.verify(FEEDBACK_ID, 9L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(VerificationErrorCode.NOT_REVIEWING));
        verify(inputPort, never()).load(FEEDBACK_ID);
    }

    @Test
    @DisplayName("담당 검수자가 아니면 403 으로 거부한다")
    void rejectsNonOwnerReviewer() {
        when(excludeContextPort.find(FEEDBACK_ID)).thenReturn(Optional.of(reviewingContext(9L)));

        assertThatThrownBy(() -> service.verify(FEEDBACK_ID, 7L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(VerificationErrorCode.NOT_REVIEWER));
        verify(inputPort, never()).load(FEEDBACK_ID);
    }

    @Test
    @DisplayName("아직 claim 되지 않은(reviewed_by_id 없음) 신고면 403 으로 거부한다")
    void rejectsUnclaimedFeedback() {
        when(excludeContextPort.find(FEEDBACK_ID)).thenReturn(Optional.of(reviewingContext(null)));

        assertThatThrownBy(() -> service.verify(FEEDBACK_ID, 9L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(VerificationErrorCode.NOT_REVIEWER));
    }

    @Test
    @DisplayName("대기 중인 교정 후보가 없으면 409 로 거부한다")
    void rejectsNoPendingCandidates() {
        when(excludeContextPort.find(FEEDBACK_ID)).thenReturn(Optional.of(reviewingContext(9L)));
        when(candidatesPort.load(FEEDBACK_ID))
                .thenReturn(new PendingCandidates("tag_correction", List.of(), List.of()));

        assertThatThrownBy(() -> service.verify(FEEDBACK_ID, 9L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(VerificationErrorCode.NO_PENDING_CANDIDATES));
        verify(inputPort, never()).load(FEEDBACK_ID);
        verify(record, never()).start(any());
    }
}
