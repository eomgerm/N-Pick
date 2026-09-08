package com.npick.search.infrastructure.persistence.query;

import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.npick.common.error.BusinessException;
import com.npick.search.application.query.candidate.FindSceneCandidatesQueryPort;
import com.npick.search.application.query.candidate.SceneCandidateResult;
import com.npick.search.domain.error.SearchErrorCode;
import com.npick.search.infrastructure.config.SceneCandidateProperties;

/**
 * pg_search(BM25) 로 후보 장면을 찾는다 (FR-SRH-001, FRD F-05·§11).
 *
 * <p>JPA Entity 도 Spring Data Repository 도 만들지 않는다. 돌려주는 것이 Aggregate 가 아니라 Projection 이고(설계 정본 §9), pg_search 의
 * {@code @@@}·{@code paradedb.boost} 는 JPQL 로 표현할 수 없다. 이 조회만을 위해 {@code SceneJpaEntity} 를 만드는 것은 정본 §17 이 금지한
 * 보일러플레이트다.
 */
@Repository
class WordSceneCandidateAdapter implements FindSceneCandidatesQueryPort {

    /**
     * 스키마를 명시하는 이유: JDBC 연결의 기본 {@code search_path} 에 {@code npick} 이 없다. Hibernate 는 {@code hibernate.default_schema}
     * 로 스스로 붙이지만 이 SQL 은 직접 붙여야 한다.
     *
     * <p>활성 처리 조인이 세대 격리다. 재처리하면 같은 클립에 새 {@code pipeline_run} 과 새 장면이 생기므로, 조인을 빼면 구·신 장면이 함께 올라온다 (F-14 "구·신 장면을 무작위로
     * 섞지 않는다").
     *
     * <p>캡션·대사 가중치는 {@code paradedb.boost} 로 인덱스 <b>안</b> 에서, 화면 글자 가중치는 밖에서 곱한다. 앞의 둘은 한 인덱스의 두 칸이라 점수가 하나로 합산되어 나오지만,
     * 화면 글자는 다른 표의 다른 인덱스라 점수가 따로 나오기 때문이다.
     *
     * <p>화면 글자를 {@code max} 로 모으는 것은 장면당 키프레임 수가 다르기 때문이다. 합으로 모으면 키프레임이 많은 장면이 내용과 무관하게 이기고, 이는 BM25 가 문서 길이 정규화로 막는
     * 편향을 밖에서 되살리는 것이다.
     */
    private static final String FIND_CANDIDATES_SQL = """
            WITH query_tokens AS (
                SELECT string_to_array(:tokens, ' ') AS tokens
            ),
            text_hits AS (
                SELECT s.scene_id, s.clip_id, paradedb.score(s) AS score
                FROM npick.scene s
                JOIN npick.clip c ON c.clip_id = s.clip_id
                    AND c.active_pipeline_run_id = s.pipeline_run_id
                WHERE s @@@ paradedb.boolean(should => ARRAY[
                    paradedb.boost(CAST(:captionWeight AS real),
                        paradedb.term_set('caption_tokens', (SELECT tokens FROM query_tokens))),
                    paradedb.boost(CAST(:transcriptWeight AS real),
                        paradedb.term_set('transcript_tokens', (SELECT tokens FROM query_tokens)))])
            ),
            ocr_hits AS (
                SELECT k.scene_id, s.clip_id, max(paradedb.score(o)) AS score
                FROM npick.ocr_observation o
                JOIN npick.keyframe k ON k.keyframe_id = o.keyframe_id
                JOIN npick.scene s ON s.scene_id = k.scene_id
                JOIN npick.clip c ON c.clip_id = s.clip_id
                    AND c.active_pipeline_run_id = s.pipeline_run_id
                WHERE o @@@ paradedb.term_set('tokens', (SELECT tokens FROM query_tokens))
                GROUP BY k.scene_id, s.clip_id
            )
            SELECT scene_id, clip_id, text_score, ocr_score, total
            FROM (
                SELECT coalesce(t.scene_id, o.scene_id) AS scene_id,
                       coalesce(t.clip_id, o.clip_id) AS clip_id,
                       coalesce(t.score, 0) AS text_score,
                       coalesce(o.score, 0) AS ocr_score,
                       coalesce(t.score, 0)
                           + CAST(:ocrWeight AS real) * coalesce(o.score, 0) AS total
                FROM text_hits t
                FULL JOIN ocr_hits o ON o.scene_id = t.scene_id
            ) merged
            WHERE total > 0
            ORDER BY total DESC, scene_id
            LIMIT :poolSize
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final SceneCandidateProperties properties;

    WordSceneCandidateAdapter(NamedParameterJdbcTemplate jdbcTemplate, SceneCandidateProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    @Override
    public List<SceneCandidateResult> findByWords(List<String> searchTokens) {
        String tokens = joinTokens(searchTokens);
        // 토큰이 없으면 DB 를 부르지 않는다. 빈 배열로 질의하면 pg_search 는 오류 없이 0건을 주는데,
        // 그것은 "검색어에 내용어가 없었다" 와 "색인에 없었다" 를 구분할 수 없게 만든다.
        if (tokens.isEmpty()) return List.of();

        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tokens", tokens)
                .addValue("captionWeight", properties.captionWeight())
                .addValue("transcriptWeight", properties.transcriptWeight())
                .addValue("ocrWeight", properties.ocrWeight())
                .addValue("poolSize", properties.poolSize());

        return jdbcTemplate.query(
                FIND_CANDIDATES_SQL,
                parameters,
                (row, rowNumber) -> new SceneCandidateResult(
                        row.getLong("scene_id"),
                        row.getLong("clip_id"),
                        row.getDouble("total"),
                        row.getDouble("text_score"),
                        row.getDouble("ocr_score")));
    }

    /**
     * 토큰을 공백으로 이어 SQL 의 {@code string_to_array} 에 넘긴다.
     *
     * <p>토큰 하나에 공백이 있으면 DB 에서 두 토큰으로 쪼개져 검색어가 조용히 달라진다. Kiwi 형태소는 공백을 품지 않으므로 이런 값은 규약 위반이며, 결과를 왜곡하는 대신 거부한다.
     */
    private static String joinTokens(List<String> searchTokens) {
        StringBuilder joined = new StringBuilder();
        for (String token : searchTokens) {
            if (token == null || token.isBlank()) continue;
            if (token.codePoints().anyMatch(Character::isWhitespace)) {
                throw new BusinessException(SearchErrorCode.SEARCH_TOKEN_CONTAINS_WHITESPACE);
            }
            if (!joined.isEmpty()) joined.append(' ');
            joined.append(token);
        }
        return joined.toString();
    }
}
