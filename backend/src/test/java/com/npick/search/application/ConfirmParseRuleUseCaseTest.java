package com.npick.search.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.search.domain.repository.SearchRuleConfirmationRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfirmParseRuleUseCaseTest {

    private SearchRuleConfirmationRepository repository;
    private ConfirmParseRuleUseCase useCase;

    @BeforeEach
    void setUp() {
        repository = mock(SearchRuleConfirmationRepository.class);
        useCase = new ConfirmParseRuleUseCase(repository);
    }

    @Test
    @DisplayName("교체 대상이 있으면 후보를 활성화하고 교체 대상을 비활성화한다")
    void activatesAndDeactivatesOnReplace() {
        useCase.confirm(9901L, 6602L, 6601L);

        verify(repository).activate(9901L, 6602L);
        verify(repository).deactivate(6601L);
    }

    @Test
    @DisplayName("교체 대상이 없으면 후보만 활성화하고 비활성화는 하지 않는다")
    void activatesOnlyWhenNoReplace() {
        useCase.confirm(9901L, 6602L, null);

        verify(repository).activate(9901L, 6602L);
        verify(repository, never()).deactivate(anyLong());
    }

    @Test
    @DisplayName("교체 지정 시 후보 활성화와 교체 대상 비활성화가 모두 적용되면 1")
    void returnsOneWhenReplaceFullyApplied() {
        when(repository.activate(9901L, 6602L)).thenReturn(1);
        when(repository.deactivate(6601L)).thenReturn(1);

        assertThat(useCase.confirm(9901L, 6602L, 6601L)).isEqualTo(1);
    }

    @Test
    @DisplayName("교체 대상이 이미 비활성이라 비활성화가 0행이면 반쪽 교체를 0으로 막는다")
    void returnsZeroWhenReplaceDeactivatesNothing() {
        when(repository.activate(9901L, 6602L)).thenReturn(1);
        when(repository.deactivate(6601L)).thenReturn(0);

        assertThat(useCase.confirm(9901L, 6602L, 6601L)).isEqualTo(0);
    }

    @Test
    @DisplayName("교체 대상이 없을 때는 후보 활성화 행 수를 그대로 돌려준다")
    void returnsActivatedCountWhenNoReplace() {
        when(repository.activate(9901L, 6602L)).thenReturn(1);

        assertThat(useCase.confirm(9901L, 6602L, null)).isEqualTo(1);
    }
}
