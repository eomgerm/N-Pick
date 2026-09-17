package com.npick.search.infrastructure.persistence.query;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.npick.common.error.BusinessException;
import com.npick.search.application.error.SearchRuleErrorCode;
import com.npick.search.application.query.exclusion.FindActiveSceneExclusionsQueryPort.ActiveSceneExclusion;
import com.npick.search.domain.model.NormalizedSearch;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActiveSceneExclusionQueryAdapterTest {

    @Test
    void failsTheSearchInsteadOfTreatingALookupFailureAsNoRule() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(
                        anyString(),
                        any(MapSqlParameterSource.class),
                        org.mockito.ArgumentMatchers.<RowMapper<ActiveSceneExclusion>>any()))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        var adapter = new ActiveSceneExclusionQueryAdapter(jdbc);
        var search = NormalizedSearch.of("제주 불꽃놀이", Map.of("region", List.of("제주")), "v1");

        assertThatThrownBy(() -> adapter.find(search))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", SearchRuleErrorCode.RULE_LOOKUP_FAILED);
    }
}
