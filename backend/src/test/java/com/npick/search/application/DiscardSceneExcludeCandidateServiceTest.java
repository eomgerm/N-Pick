package com.npick.search.application;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.common.error.BusinessException;
import com.npick.common.persistence.CorrectionStateLock;
import com.npick.search.application.error.SceneExcludeCandidateErrorCode;
import com.npick.search.application.port.ExcludeContext;
import com.npick.search.application.port.ExcludeContextPort;
import com.npick.search.domain.repository.SceneExcludeCandidateRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiscardSceneExcludeCandidateServiceTest {

    private ExcludeContextPort excludeContextPort;
    private SceneExcludeCandidateRepository candidateRepository;
    private CorrectionStateLock correctionStateLock;
    private DiscardSceneExcludeCandidateService service;

    @BeforeEach
    void setUp() {
        excludeContextPort = mock(ExcludeContextPort.class);
        candidateRepository = mock(SceneExcludeCandidateRepository.class);
        correctionStateLock = mock(CorrectionStateLock.class);
        service = new DiscardSceneExcludeCandidateService(excludeContextPort, candidateRepository, correctionStateLock);
    }

    private void reviewingExclude() {
        when(excludeContextPort.find(1L))
                .thenReturn(Optional.of(
                        new ExcludeContext("REVIEWING", "exclude_scene", 9L, 300L, "fp-1", "제주 불꽃놀이", "{}", "v1")));
    }

    @Test
    @DisplayName("전제가 맞으면 대기 후보를 지우고 지운 수를 준다")
    void discardsPendingCandidate() {
        reviewingExclude();
        when(candidateRepository.discardByFeedback(1L)).thenReturn(1);

        int discarded = service.discard(1L, 9L, true);

        verify(correctionStateLock).acquire();
        assertThat(discarded).isEqualTo(1);
        verify(candidateRepository).discardByFeedback(1L);
    }

    @Test
    @DisplayName("편집기자면 거부한다")
    void rejectsEditor() {
        assertThatThrownBy(() -> service.discard(1L, 9L, false))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(SceneExcludeCandidateErrorCode.EDITOR_FORBIDDEN));
        verify(candidateRepository, never()).discardByFeedback(1L);
        verify(correctionStateLock, never()).acquire();
    }

    @Test
    @DisplayName("없는 신고면 거부한다")
    void rejectsMissingFeedback() {
        when(excludeContextPort.find(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.discard(1L, 9L, true))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(SceneExcludeCandidateErrorCode.FEEDBACK_NOT_FOUND));
    }

    @Test
    @DisplayName("검수 중이 아니면 거부한다")
    void rejectsNotReviewing() {
        when(excludeContextPort.find(1L))
                .thenReturn(
                        Optional.of(new ExcludeContext("CLOSED", "exclude_scene", 9L, 300L, "fp-1", "q", "{}", "v1")));
        assertThatThrownBy(() -> service.discard(1L, 9L, true))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(SceneExcludeCandidateErrorCode.NOT_REVIEWING));
        verify(candidateRepository, never()).discardByFeedback(1L);
    }

    @Test
    @DisplayName("담당 검수자가 아니면 거부한다")
    void rejectsNonOwnerReviewer() {
        reviewingExclude();
        assertThatThrownBy(() -> service.discard(1L, 7L, true))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(SceneExcludeCandidateErrorCode.NOT_REVIEWER));
        verify(candidateRepository, never()).discardByFeedback(1L);
    }
}
