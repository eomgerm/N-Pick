package com.npick.search.infrastructure.persistence.query;

import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.npick.common.error.BusinessException;
import com.npick.search.application.error.SearchRuleErrorCode;
import com.npick.search.application.query.exclusion.FindActiveSceneExclusionsQueryPort;
import com.npick.search.domain.model.NormalizedSearch;

/** 활성 {@code exclude_scene} 규칙의 exact 검색 조회 어댑터. */
@Repository
class ActiveSceneExclusionQueryAdapter implements FindActiveSceneExclusionsQueryPort {

    private static final String SQL = """
            SELECT search_rule_id, target_scene_id
            FROM npick.search_rule
            WHERE action = 'exclude_scene'
              AND active = true
              AND query_fingerprint = :fingerprint
              AND normalized_query = :normalizedQuery
              AND normalization_version = :normalizationVersion
              AND normalized_filters_json = CAST(:normalizedFiltersJson AS jsonb)
            ORDER BY search_rule_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    ActiveSceneExclusionQueryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ActiveSceneExclusion> find(NormalizedSearch search) {
        try {
            var parameters = new MapSqlParameterSource()
                    .addValue("fingerprint", search.fingerprint())
                    .addValue("normalizedQuery", search.normalizedQuery())
                    .addValue("normalizationVersion", search.normalizationVersion())
                    .addValue("normalizedFiltersJson", objectMapper.writeValueAsString(search.normalizedFilters()));
            return jdbc.query(
                    SQL,
                    parameters,
                    (row, rowNumber) ->
                            new ActiveSceneExclusion(row.getLong("search_rule_id"), row.getLong("target_scene_id")));
        } catch (DataAccessException | JsonProcessingException ex) {
            // 활성 제외를 읽지 못했는데 빈 목록으로 계속하면 사람의 승인 결정을 조용히 건너뛴다 (§6.2).
            throw new BusinessException(SearchRuleErrorCode.RULE_LOOKUP_FAILED, ex);
        }
    }
}
