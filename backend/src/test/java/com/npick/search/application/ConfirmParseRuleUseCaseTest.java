package com.npick.search.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.search.domain.repository.SearchRuleConfirmationRepository;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
        verify(repository, never()).deactivate(org.mockito.ArgumentMatchers.anyLong());
    }
}
