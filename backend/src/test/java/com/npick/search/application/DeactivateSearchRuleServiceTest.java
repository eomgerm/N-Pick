package com.npick.search.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.common.error.BusinessException;
import com.npick.common.persistence.CorrectionStateLock;
import com.npick.search.application.error.SearchRuleDeactivationErrorCode;
import com.npick.search.domain.repository.SearchRuleDeactivationRepository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeactivateSearchRuleServiceTest {

    private static final long RULE = 6601L;

    private SearchRuleDeactivationRepository repository;
    private DeactivateSearchRuleService service;

    @BeforeEach
    void setUp() {
        repository = mock(SearchRuleDeactivationRepository.class);
        service = new DeactivateSearchRuleService(repository, mock(CorrectionStateLock.class));
    }

    private DeactivateSearchRuleCommand command(boolean reviewerRole, boolean active) {
        return new DeactivateSearchRuleCommand(RULE, reviewerRole, active, "잘못된 규칙이라 중단");
    }

    @Test
    @DisplayName("편집기자는 규칙을 끌 수 없다")
    void editorForbidden() {
        assertThatThrownBy(() -> service.deactivate(command(false, false)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(SearchRuleDeactivationErrorCode.EDITOR_FORBIDDEN);
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("검증 없이 재활성화(active=true) 요청은 거부한다")
    void rejectsReactivation() {
        assertThatThrownBy(() -> service.deactivate(command(true, true)))
                .extracting("errorCode")
                .isEqualTo(SearchRuleDeactivationErrorCode.CANNOT_REACTIVATE);
        verify(repository, never()).deactivate(anyLong());
    }

    @Test
    @DisplayName("활성 규칙을 끈다")
    void deactivates() {
        when(repository.deactivate(RULE)).thenReturn(1);

        service.deactivate(command(true, false));

        verify(repository).deactivate(RULE);
    }

    @Test
    @DisplayName("이미 꺼진 규칙이면 409")
    void alreadyInactive() {
        when(repository.deactivate(RULE)).thenReturn(0);
        when(repository.exists(RULE)).thenReturn(true);

        assertThatThrownBy(() -> service.deactivate(command(true, false)))
                .extracting("errorCode")
                .isEqualTo(SearchRuleDeactivationErrorCode.ALREADY_INACTIVE);
    }

    @Test
    @DisplayName("없는 규칙이면 404")
    void ruleNotFound() {
        when(repository.deactivate(RULE)).thenReturn(0);
        when(repository.exists(RULE)).thenReturn(false);

        assertThatThrownBy(() -> service.deactivate(command(true, false)))
                .extracting("errorCode")
                .isEqualTo(SearchRuleDeactivationErrorCode.RULE_NOT_FOUND);
    }
}
