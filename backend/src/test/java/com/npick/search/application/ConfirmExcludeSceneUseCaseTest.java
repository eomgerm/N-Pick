package com.npick.search.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.search.domain.repository.SearchRuleConfirmationRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfirmExcludeSceneUseCaseTest {

    private SearchRuleConfirmationRepository repository;
    private ConfirmExcludeSceneUseCase useCase;

    @BeforeEach
    void setUp() {
        repository = mock(SearchRuleConfirmationRepository.class);
        useCase = new ConfirmExcludeSceneService(repository);
    }

    @Test
    @DisplayName("신고 범위의 제외 규칙을 활성화하고 활성화된 행 수를 돌려준다")
    void activatesExcludeRule() {
        when(repository.activate(9901L, 6601L)).thenReturn(1);

        assertThat(useCase.confirm(9901L, 6601L)).isEqualTo(1);
        verify(repository).activate(9901L, 6601L);
    }

    @Test
    @DisplayName("후보가 이미 적용됐거나 사라져 활성화가 0행이면 0을 돌려준다")
    void returnsZeroWhenNothingActivated() {
        when(repository.activate(9901L, 6601L)).thenReturn(0);

        assertThat(useCase.confirm(9901L, 6601L)).isEqualTo(0);
    }
}
