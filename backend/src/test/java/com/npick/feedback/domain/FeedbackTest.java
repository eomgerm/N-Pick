package com.npick.feedback.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.feedback.domain.model.Feedback;
import com.npick.feedback.domain.model.FeedbackStatus;

import static org.assertj.core.api.Assertions.assertThat;

class FeedbackTest {

    @Test
    @DisplayName("새 신고는 open 상태로 생성되고 식별자가 부여된다")
    void openStartsInOpenWithId() {
        Feedback fb = Feedback.open(10L, 20L, "이상해요");
        assertThat(fb.status()).isEqualTo(FeedbackStatus.OPEN);
        assertThat(fb.feedbackId()).isPositive();
        assertThat(fb.searchResultId()).isEqualTo(10L);
        assertThat(fb.createdById()).isEqualTo(20L);
        assertThat(fb.isOpen()).isTrue();
    }

    @Test
    @DisplayName("소유자 판별은 생성자 memberId로만 참이다")
    void isOwnedByMatchesCreator() {
        Feedback fb = Feedback.open(10L, 20L, null);
        assertThat(fb.isOwnedBy(20L)).isTrue();
        assertThat(fb.isOwnedBy(99L)).isFalse();
    }
}
