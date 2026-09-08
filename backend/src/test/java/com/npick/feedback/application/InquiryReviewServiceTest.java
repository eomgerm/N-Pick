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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class InquiryReviewServiceTest {

    @Mock
    InquiryListQuery listQuery;

    @Mock
    InquiryDetailQuery detailQuery;

    InquiryReviewService service;

    @BeforeEach
    void setUp() {
        service = new InquiryReviewService(listQuery, detailQuery);
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
}
