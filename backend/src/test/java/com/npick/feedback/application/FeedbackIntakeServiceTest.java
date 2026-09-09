package com.npick.feedback.application;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.npick.feedback.domain.error.FeedbackException;
import com.npick.feedback.domain.model.Feedback;
import com.npick.feedback.domain.repository.FeedbackRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedbackIntakeServiceTest {

    @Mock
    FeedbackRepository repository;

    FeedbackIntakeService service;

    @BeforeEach
    void setUp() {
        service = new FeedbackIntakeService(repository);
    }

    @Test
    @DisplayName("저장 안 된 검색 결과에는 접수를 거부한다")
    void rejectsUnsavedResult() {
        when(repository.existsSearchResult(5L)).thenReturn(false);
        assertThatThrownBy(() -> service.submit(5L, 20L, "c")).isInstanceOf(FeedbackException.class);
    }

    @Test
    @DisplayName("이미 접수한 (결과,신고자)면 기존 문의를 돌려준다")
    void returnsExistingOnDuplicate() {
        when(repository.existsSearchResult(5L)).thenReturn(true);
        when(repository.findByResultAndCreator(5L, 20L)).thenReturn(Optional.of(Feedback.open(5L, 20L, "old")));
        Feedback out = service.submit(5L, 20L, "new");
        assertThat(out.comment()).isEqualTo("old");
    }

    @Test
    @DisplayName("동시에 중복 접수되어 유니크 제약을 위반하면 기존 문의를 멱등 반환한다")
    void returnsExistingOnConcurrentDuplicateRace() {
        Feedback existing = Feedback.open(5L, 20L, "old");
        when(repository.existsSearchResult(5L)).thenReturn(true);
        when(repository.findByResultAndCreator(5L, 20L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(repository.save(any(Feedback.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq"));

        Feedback out = service.submit(5L, 20L, "new");

        assertThat(out).isEqualTo(existing);
    }

    @Test
    @DisplayName("신규면 open으로 저장한다")
    void savesNewPending() {
        when(repository.existsSearchResult(5L)).thenReturn(true);
        when(repository.findByResultAndCreator(5L, 20L)).thenReturn(Optional.empty());
        when(repository.save(any(Feedback.class))).thenAnswer(i -> i.getArgument(0));
        Feedback out = service.submit(5L, 20L, "new");
        assertThat(out.isOpen()).isTrue();
        assertThat(out.comment()).isEqualTo("new");
    }

    @Test
    @DisplayName("open이 아닌 문의 수정은 거부한다")
    void rejectsEditWhenNotPending() {
        var reviewing = new com.npick.feedback.domain.model.Feedback(
                1L,
                5L,
                20L,
                "c",
                com.npick.feedback.domain.model.FeedbackStatus.REVIEWING,
                9L,
                java.time.Instant.EPOCH);
        when(repository.findById(1L)).thenReturn(Optional.of(reviewing));
        assertThatThrownBy(() -> service.editComment(1L, 20L, "new")).isInstanceOf(FeedbackException.class);
    }

    @Test
    @DisplayName("남의 문의 수정은 거부한다")
    void rejectsEditByNonOwner() {
        when(repository.findById(1L)).thenReturn(Optional.of(Feedback.open(5L, 20L, "c")));
        assertThatThrownBy(() -> service.editComment(1L, 99L, "new")).isInstanceOf(FeedbackException.class);
    }

    @Test
    @DisplayName("본인 open 문의는 설명을 수정한다")
    void editsOwnPending() {
        when(repository.findById(1L)).thenReturn(Optional.of(Feedback.open(5L, 20L, "c")));
        when(repository.editComment(1L, 20L, "new")).thenReturn(1);
        service.editComment(1L, 20L, "new"); // 예외 없이 통과
    }
}
