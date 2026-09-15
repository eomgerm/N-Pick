package com.npick.search.infrastructure.persistence.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import com.npick.common.error.BusinessException;
import com.npick.search.application.error.SearchRuleErrorCode;
import com.npick.search.infrastructure.persistence.mapper.ParseRuleJsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 규칙 조회 실패가 빈 목록으로 삼켜지지 않는지 확인한다 (FRD §6.2 「활성 규칙 조회 실패 → 사람의 결정을 조용히 건너뛰지 않고 검색 실패·재시도 안내」).
 *
 * <p>이 한 줄이 없으면 DB 장애 때 검색이 「교정 규칙이 없는 정상 검색」으로 보인다. 사용자는 무엇이 빠졌는지 알 수 없다.
 */
class ParseRuleRepositoryAdapterTest {

    private final SearchRuleJpaRepository jpaRepository = mock(SearchRuleJpaRepository.class);
    private final ParseRuleRepositoryAdapter adapter =
            new ParseRuleRepositoryAdapter(jpaRepository, new ParseRuleJsonMapper());

    @Test
    @DisplayName("조회가 실패하면 빈 목록이 아니라 검색 실패로 올린다")
    void translatesLookupFailureIntoASearchFailure() {
        when(jpaRepository.findByActionAndActiveIsTrueOrderBySearchRuleIdAsc(anyString()))
                .thenThrow(new QueryTimeoutException("조회 시간 초과"));

        assertThatThrownBy(adapter::findActivePatchParseRules)
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(SearchRuleErrorCode.RULE_LOOKUP_FAILED);
    }

    @Test
    @DisplayName("patch_parse 만 조회한다 — 장면 제외는 다른 기능이다")
    void looksUpOnlyPatchParseRules() {
        when(jpaRepository.findByActionAndActiveIsTrueOrderBySearchRuleIdAsc("patch_parse"))
                .thenReturn(java.util.List.of());

        assertThat(adapter.findActivePatchParseRules()).isEmpty();
    }
}
