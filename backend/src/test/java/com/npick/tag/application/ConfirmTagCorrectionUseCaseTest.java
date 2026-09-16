package com.npick.tag.application;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.tag.domain.repository.TagCorrectionConfirmationRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfirmTagCorrectionUseCaseTest {

    private TagCorrectionConfirmationRepository repository;
    private ConfirmTagCorrectionUseCase useCase;

    @BeforeEach
    void setUp() {
        repository = mock(TagCorrectionConfirmationRepository.class);
        useCase = new ConfirmTagCorrectionUseCase(repository);
    }

    @Test
    @DisplayName("승인 근거를 신고 범위로 확정하고 확정된 수를 돌려준다")
    void confirmsScopedToFeedback() {
        when(repository.confirm(9901L, List.of(7901L, 7902L))).thenReturn(2);

        int confirmed = useCase.confirm(9901L, List.of(7901L, 7902L));

        assertThat(confirmed).isEqualTo(2);
        verify(repository).confirm(9901L, List.of(7901L, 7902L));
    }
}
