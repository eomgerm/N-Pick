package com.npick.feedback.application;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.npick.feedback.application.query.InquiryDetailQuery;
import com.npick.feedback.application.query.InquiryListQuery;
import com.npick.feedback.domain.error.FeedbackErrorCode;
import com.npick.feedback.domain.error.FeedbackException;
import com.npick.feedback.domain.model.Feedback;
import com.npick.feedback.domain.model.FeedbackStatus;
import com.npick.feedback.domain.repository.FeedbackRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InquiryReviewServiceTest {

    @Mock
    InquiryListQuery listQuery;

    @Mock
    InquiryDetailQuery detailQuery;

    @Mock
    FeedbackRepository repository;

    InquiryReviewService service;

    @BeforeEach
    void setUp() {
        service = new InquiryReviewService(listQuery, detailQuery, repository);
    }

    @Test
    @DisplayName("status 필터는 대문자로 정규화해 조회한다")
    void normalizesStatusToUpper() {
        service.list("open", 0, 20);
        verify(listQuery).findByStatus(eq("OPEN"), eq(0), eq(20));
    }

    @Test
    @DisplayName("status 공란이면 전체(null)로 조회한다")
    void blankStatusMeansAll() {
        service.list("  ", 0, 20);
        verify(listQuery).findByStatus(isNull(), eq(0), eq(20));
    }

    @Test
    @DisplayName("존재하지 않는 문의 상세 조회는 FEEDBACK_NOT_FOUND")
    void detailThrowsWhenNotFound() {
        given(detailQuery.findById(1L)).willReturn(Optional.empty());

        FeedbackException ex = catchThrowableOfType(FeedbackException.class, () -> service.detail(1L));
        assertThat(ex.errorCode()).isEqualTo(FeedbackErrorCode.FEEDBACK_NOT_FOUND);
    }

    @Test
    @DisplayName("이미 reviewing이면 claim은 409(ALREADY_CLAIMED)")
    void claimConflictWhenNotOpen() {
        when(repository.findById(1L)).thenReturn(Optional.of(Feedback.open(5L, 20L, null)));
        when(repository.claim(
                        org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.eq(9L),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(0);
        assertThatThrownBy(() -> service.claim(1L, 9L)).isInstanceOf(FeedbackException.class);
    }

    @Test
    @DisplayName("open이면 claim으로 reviewing 전환한다")
    void claimSucceedsOnOpen() {
        when(repository.findById(1L)).thenReturn(Optional.of(Feedback.open(5L, 20L, null)));
        when(repository.claim(
                        org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.eq(9L),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(1);
        service.claim(1L, 9L); // 예외 없이 통과
    }

    private static Feedback reviewing(long reviewerId) {
        return new Feedback(1L, 5L, 20L, null, FeedbackStatus.REVIEWING, reviewerId, java.time.Instant.EPOCH);
    }

    @Test
    @DisplayName("모르는 처리 결과는 400(INVALID_RESOLUTION)")
    void rejectsUnknownResolution() {
        FeedbackException ex = catchThrowableOfType(FeedbackException.class, () -> service.resolve(1L, 9L, "nope", "n"));
        assertThat(ex.errorCode()).isEqualTo(FeedbackErrorCode.INVALID_RESOLUTION);
    }

    @Test
    @DisplayName("no_action·deferred는 사유 없으면 400(NOTE_REQUIRED)")
    void rejectsTerminalWithoutNote() {
        FeedbackException ex =
                catchThrowableOfType(FeedbackException.class, () -> service.resolve(1L, 9L, "no_action", "  "));
        assertThat(ex.errorCode()).isEqualTo(FeedbackErrorCode.NOTE_REQUIRED);
    }

    @Test
    @DisplayName("reviewing이 아니면 409(NOT_RESOLVABLE)")
    void rejectsWhenNotReviewing() {
        given(repository.findById(1L)).willReturn(Optional.of(Feedback.open(5L, 20L, null)));
        FeedbackException ex =
                catchThrowableOfType(FeedbackException.class, () -> service.resolve(1L, 9L, "patch_parse", null));
        assertThat(ex.errorCode()).isEqualTo(FeedbackErrorCode.NOT_RESOLVABLE);
    }

    @Test
    @DisplayName("담당 검수자가 아니면 403(NOT_REVIEWER)")
    void rejectsWhenNotClaimer() {
        given(repository.findById(1L)).willReturn(Optional.of(reviewing(7L)));
        FeedbackException ex =
                catchThrowableOfType(FeedbackException.class, () -> service.resolve(1L, 9L, "patch_parse", null));
        assertThat(ex.errorCode()).isEqualTo(FeedbackErrorCode.NOT_REVIEWER);
    }

    @Test
    @DisplayName("교정 판정은 reviewing 유지·closed_at 없이 기록한다")
    void correctionKeepsReviewing() {
        given(repository.findById(1L)).willReturn(Optional.of(reviewing(9L)));
        given(repository.resolve(
                        eq(1L), eq(9L), eq("patch_parse"), isNull(), eq("REVIEWING"), isNull(), org.mockito.ArgumentMatchers.any()))
                .willReturn(1);
        service.resolve(1L, 9L, "patch_parse", null);
        verify(repository)
                .resolve(eq(1L), eq(9L), eq("patch_parse"), isNull(), eq("REVIEWING"), isNull(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("종료성 판정은 closed 상태·closed_at과 함께 기록한다")
    void terminalClosesFeedback() {
        given(repository.findById(1L)).willReturn(Optional.of(reviewing(9L)));
        given(repository.resolve(
                        eq(1L),
                        eq(9L),
                        eq("no_action"),
                        eq("문제 없음"),
                        eq("CLOSED"),
                        org.mockito.ArgumentMatchers.notNull(),
                        org.mockito.ArgumentMatchers.any()))
                .willReturn(1);
        service.resolve(1L, 9L, "no_action", "문제 없음");
        verify(repository)
                .resolve(
                        eq(1L),
                        eq(9L),
                        eq("no_action"),
                        eq("문제 없음"),
                        eq("CLOSED"),
                        org.mockito.ArgumentMatchers.notNull(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("CAS 0행(경합)이면 409(NOT_RESOLVABLE)")
    void resolveLostRaceConflict() {
        given(repository.findById(1L)).willReturn(Optional.of(reviewing(9L)));
        given(repository.resolve(
                        eq(1L),
                        eq(9L),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .willReturn(0);
        FeedbackException ex =
                catchThrowableOfType(FeedbackException.class, () -> service.resolve(1L, 9L, "patch_parse", null));
        assertThat(ex.errorCode()).isEqualTo(FeedbackErrorCode.NOT_RESOLVABLE);
    }
}
