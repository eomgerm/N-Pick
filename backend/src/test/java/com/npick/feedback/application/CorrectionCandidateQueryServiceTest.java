package com.npick.feedback.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.feedback.application.query.CorrectionCandidates;
import com.npick.feedback.domain.error.FeedbackErrorCode;
import com.npick.feedback.domain.error.FeedbackException;
import com.npick.feedback.domain.model.Feedback;
import com.npick.feedback.domain.model.FeedbackStatus;
import com.npick.feedback.domain.repository.FeedbackRepository;
import com.npick.search.application.query.pending.ListPendingSearchRuleCandidatesUseCase;
import com.npick.search.application.query.pending.PendingSearchRuleCandidates;
import com.npick.tag.application.TagCorrectionAction;
import com.npick.tag.application.TagScope;
import com.npick.tag.application.query.ListPendingTagCorrectionCandidatesUseCase;
import com.npick.tag.application.query.PendingTagCorrectionCandidate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CorrectionCandidateQueryServiceTest {

    private FeedbackRepository repository;
    private ListPendingTagCorrectionCandidatesUseCase pendingTags;
    private ListPendingSearchRuleCandidatesUseCase pendingRules;
    private CorrectionCandidateQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(FeedbackRepository.class);
        pendingTags = mock(ListPendingTagCorrectionCandidatesUseCase.class);
        pendingRules = mock(ListPendingSearchRuleCandidatesUseCase.class);
        service = new CorrectionCandidateQueryService(repository, pendingTags, pendingRules);
    }

    private static Feedback feedback(FeedbackStatus status, Long reviewerId) {
        return new Feedback(1L, 5L, 20L, null, status, reviewerId, Instant.EPOCH);
    }

    @Test
    @DisplayName("담당 검수자에게 태그·해석·장면 제외 대기 후보를 모아 돌려준다")
    void collectsPendingCandidatesFromTagAndSearch() {
        when(repository.findById(1L)).thenReturn(Optional.of(feedback(FeedbackStatus.REVIEWING, 9L)));
        when(pendingTags.listPending(1L))
                .thenReturn(List.of(new PendingTagCorrectionCandidate(
                        5001L, 5501L, TagCorrectionAction.WITHDRAW, TagScope.CLIP, "event", "포항지진", "포항 지진")));
        when(pendingRules.listPending(1L))
                .thenReturn(new PendingSearchRuleCandidates(
                        List.of(new PendingSearchRuleCandidates.ParsePatch(6602L, "{\"c\":1}", "{\"p\":1}", 6601L)),
                        List.of(new PendingSearchRuleCandidates.SceneExclude(6603L, 9301L))));

        CorrectionCandidates result = service.get(1L, 9L);

        assertThat(result.tags())
                .containsExactly(new CorrectionCandidates.TagCandidate(
                        5001L, 5501L, "WITHDRAW", "CLIP", "event", "포항지진", "포항 지진"));
        assertThat(result.parsePatches())
                .containsExactly(new CorrectionCandidates.ParsePatchCandidate(6602L, "{\"c\":1}", "{\"p\":1}", 6601L));
        assertThat(result.sceneExcludes())
                .containsExactly(new CorrectionCandidates.SceneExcludeCandidate(6603L, 9301L));
    }

    @Test
    @DisplayName("검수 중이 아닌 신고는 담당 검수자여도 후보를 읽지 않고 빈 목록을 준다 — 확정 뒤 꺼진 규칙이 후보로 새지 않게")
    void closedFeedbackReturnsEmptyWithoutReadingCandidates() {
        when(repository.findById(1L)).thenReturn(Optional.of(feedback(FeedbackStatus.CLOSED, 9L)));

        CorrectionCandidates result = service.get(1L, 9L);

        assertThat(result.tags()).isEmpty();
        assertThat(result.parsePatches()).isEmpty();
        assertThat(result.sceneExcludes()).isEmpty();
        verify(pendingTags, never()).listPending(anyLong());
        verify(pendingRules, never()).listPending(anyLong());
    }

    @Test
    @DisplayName("없는 신고면 FEEDBACK_NOT_FOUND")
    void rejectsMissingFeedback() {
        when(repository.findById(1L)).thenReturn(Optional.empty());

        FeedbackException ex = catchThrowableOfType(FeedbackException.class, () -> service.get(1L, 9L));

        assertThat(ex.errorCode()).isEqualTo(FeedbackErrorCode.FEEDBACK_NOT_FOUND);
        verify(pendingTags, never()).listPending(anyLong());
    }

    @Test
    @DisplayName("다른 검수자가 잡은 신고면 NOT_REVIEWER 이고 후보를 읽지 않는다")
    void rejectsOtherReviewer() {
        when(repository.findById(1L)).thenReturn(Optional.of(feedback(FeedbackStatus.REVIEWING, 8L)));

        FeedbackException ex = catchThrowableOfType(FeedbackException.class, () -> service.get(1L, 9L));

        assertThat(ex.errorCode()).isEqualTo(FeedbackErrorCode.NOT_REVIEWER);
        verify(pendingTags, never()).listPending(anyLong());
        verify(pendingRules, never()).listPending(anyLong());
    }

    @Test
    @DisplayName("아직 아무도 잡지 않은 신고면 NOT_REVIEWER")
    void rejectsUnclaimedFeedback() {
        when(repository.findById(1L)).thenReturn(Optional.of(feedback(FeedbackStatus.OPEN, null)));

        FeedbackException ex = catchThrowableOfType(FeedbackException.class, () -> service.get(1L, 9L));

        assertThat(ex.errorCode()).isEqualTo(FeedbackErrorCode.NOT_REVIEWER);
    }
}
