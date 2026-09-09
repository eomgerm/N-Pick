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
        when(repository.claim(
                        org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.eq(9L),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(1);
        service.claim(1L, 9L); // CAS 1행 성공, 재조회 없이 통과
    }

    private static Feedback reviewingOwnedBy(long reviewerId) {
        return new Feedback(1L, 5L, 20L, null, FeedbackStatus.REVIEWING, reviewerId, java.time.Instant.EPOCH);
    }

    @Test
    @DisplayName("이미 이 검수자가 잡은 문의의 재-claim은 성공 취급한다(소유자 멱등)")
    void reclaimByOwnerIsIdempotent() {
        when(repository.findById(1L)).thenReturn(Optional.of(reviewingOwnedBy(9L)));
        when(repository.claim(
                        org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.eq(9L),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(0);
        service.claim(1L, 9L); // 0행이지만 본인 소유라 예외 없이 통과
    }

    @Test
    @DisplayName("남이 잡은 문의를 다른 검수자가 claim하면 409(ALREADY_CLAIMED)")
    void claimByNonOwnerConflicts() {
        when(repository.findById(1L)).thenReturn(Optional.of(reviewingOwnedBy(7L)));
        when(repository.claim(
                        org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.eq(9L),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(0);
        FeedbackException ex = catchThrowableOfType(FeedbackException.class, () -> service.claim(1L, 9L));
        assertThat(ex.errorCode()).isEqualTo(FeedbackErrorCode.ALREADY_CLAIMED);
    }
}
