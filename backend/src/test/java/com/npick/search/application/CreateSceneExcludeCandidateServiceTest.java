package com.npick.search.application;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.npick.common.error.BusinessException;
import com.npick.search.application.error.SceneExcludeCandidateErrorCode;
import com.npick.search.application.port.ExcludeContext;
import com.npick.search.application.port.ExcludeContextPort;
import com.npick.search.domain.model.SceneExcludeCandidate;
import com.npick.search.domain.repository.SceneExcludeCandidateRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreateSceneExcludeCandidateServiceTest {

    private ExcludeContextPort excludeContextPort;
    private SceneExcludeCandidateRepository candidateRepository;
    private CreateSceneExcludeCandidateService service;

    @BeforeEach
    void setUp() {
        excludeContextPort = mock(ExcludeContextPort.class);
        candidateRepository = mock(SceneExcludeCandidateRepository.class);
        service = new CreateSceneExcludeCandidateService(excludeContextPort, candidateRepository);
    }

    private void reviewingExclude() {
        when(excludeContextPort.find(1L))
                .thenReturn(Optional.of(
                        new ExcludeContext("REVIEWING", "exclude_scene", 9L, 300L, "fp-1", "제주 불꽃놀이", "{}", "v1")));
    }

    private CreateSceneExcludeCandidateCommand command(boolean reviewerRole, long reviewerId, long targetSceneId) {
        return new CreateSceneExcludeCandidateCommand(1L, reviewerId, reviewerRole, "rk-1", targetSceneId);
    }

    @Test
    @DisplayName("전제·대상이 맞으면 비활성 후보로 저장하고 생성 id 를 준다")
    void createsInactiveCandidate() {
        reviewingExclude();
        when(candidateRepository.findId(1L, "rk-1")).thenReturn(Optional.empty());
        when(candidateRepository.insertIfAbsent(any())).thenReturn(Optional.of(777L));

        ParseCandidateOutcome outcome = service.create(command(true, 9L, 300L));

        assertThat(outcome.searchRuleId()).isEqualTo(777L);
        assertThat(outcome.created()).isTrue();
        ArgumentCaptor<SceneExcludeCandidate> captor = ArgumentCaptor.forClass(SceneExcludeCandidate.class);
        verify(candidateRepository).insertIfAbsent(captor.capture());
        assertThat(captor.getValue().targetSceneId()).isEqualTo(300L);
        assertThat(captor.getValue().queryFingerprint()).isEqualTo("fp-1");
        assertThat(captor.getValue().normalizationVersion()).isEqualTo("v1");
    }

    @Test
    @DisplayName("편집기자면 거부한다")
    void rejectsEditor() {
        assertThatThrownBy(() -> service.create(command(false, 9L, 300L)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(SceneExcludeCandidateErrorCode.EDITOR_FORBIDDEN));
        verify(candidateRepository, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("없는 신고면 거부한다")
    void rejectsMissingFeedback() {
        when(excludeContextPort.find(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(command(true, 9L, 300L)))
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
        assertThatThrownBy(() -> service.create(command(true, 9L, 300L)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(SceneExcludeCandidateErrorCode.NOT_REVIEWING));
    }

    @Test
    @DisplayName("장면 제외로 처리된 신고가 아니면 거부한다")
    void rejectsNotExcludeScene() {
        when(excludeContextPort.find(1L))
                .thenReturn(
                        Optional.of(new ExcludeContext("REVIEWING", "no_action", 9L, 300L, "fp-1", "q", "{}", "v1")));
        assertThatThrownBy(() -> service.create(command(true, 9L, 300L)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(SceneExcludeCandidateErrorCode.NOT_EXCLUDE_SCENE));
    }

    @Test
    @DisplayName("담당 검수자가 아니면 거부한다")
    void rejectsNonOwnerReviewer() {
        reviewingExclude();
        assertThatThrownBy(() -> service.create(command(true, 7L, 300L)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(SceneExcludeCandidateErrorCode.NOT_REVIEWER));
    }

    @Test
    @DisplayName("신고 장면과 다른 장면을 지정하면 거부한다")
    void rejectsWrongTargetScene() {
        reviewingExclude();
        when(candidateRepository.findId(1L, "rk-1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(command(true, 9L, 999L)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(SceneExcludeCandidateErrorCode.WRONG_TARGET_SCENE));
        verify(candidateRepository, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("같은 요청키로 다시 부르면 저장하지 않고 기존 후보 id 를 준다")
    void idempotentReturnsExisting() {
        reviewingExclude();
        when(candidateRepository.findId(1L, "rk-1")).thenReturn(Optional.of(555L));

        ParseCandidateOutcome outcome = service.create(command(true, 9L, 300L));

        assertThat(outcome.searchRuleId()).isEqualTo(555L);
        assertThat(outcome.created()).isFalse();
        verify(candidateRepository, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("동시 저장으로 유니크 위반이 나면 500 이 아니라 기존 후보를 existing 으로 복구한다")
    void recoversFromConcurrentDuplicate() {
        reviewingExclude();
        when(candidateRepository.findId(1L, "rk-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(999L));
        when(candidateRepository.insertIfAbsent(any())).thenReturn(Optional.empty());

        ParseCandidateOutcome outcome = service.create(command(true, 9L, 300L));

        assertThat(outcome.searchRuleId()).isEqualTo(999L);
        assertThat(outcome.created()).isFalse();
    }
}
