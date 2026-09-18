package com.npick.search.infrastructure.persistence.query;

import java.util.List;
import java.util.Objects;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.npick.common.error.BusinessException;
import com.npick.search.application.error.SceneCandidateErrorCode;
import com.npick.search.application.query.candidate.FindSceneCandidatesQueryPort;
import com.npick.search.application.query.candidate.SceneCandidateResult;
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
     * <p>클립 조인이 검색 가능 여부를 정한다. FRD F-14 는 <b>두 조건</b>을 요구한다 — 「영상의 검색 가능 여부는 현재 제공 중인 {@code active_pipeline_run_id} 와
     * 논리 삭제 여부로 판단한다」({@code docs/frd.md} §6.1). 앞의 조건이 세대 격리고(재처리하면 같은 클립에 새 {@code pipeline_run} 과 새 장면이 생겨, 빼면 구·신
     * 장면이 섞인다), 뒤의 {@code deleted_at IS NULL} 이 논리 삭제다. 하드 삭제를 하지 않으므로 이 조건을 빼면 삭제한 클립이 영원히 검색된다.
     *
     * <p><b>두 채널의 점수는 같은 척도가 아니다.</b> 캡션·대사는 {@code ix_scene_bm25}, 화면 글자는 {@code ix_ocr_bm25} 에서 나오는데 두 인덱스는 문서 집합이 달라
     * IDF 분포가 다르다. 고정된 {@code ocrWeight} 하나로는 질의마다 달라지는 척도 차이를 맞출 수 없다 — BM25 와 dense 를 RRF 로 묶는 이유와 같은 문제가 여기서는
     * lexical 채널 둘 사이에 있다. ponytail: 순위 기반 결합 대신 가중합으로 두었고, Gate B 실측에서 {@code ocrWeight} 로 보정한다. 실측으로도 안 맞으면 이 자리를 RRF
     * 로 올린다.
     *
     * <p>{@code paradedb.score(<테이블 별칭>)} 과 두 인자짜리 {@code paradedb.term_set(<필드>, <text[]>)} 는 공개 문서에 없는 형태다. 0.25.6
     * 컨테이너의 {@code pg_proc} 에서 {@code score(relation_reference anyelement)} 와 {@code term_set(field fieldname, terms
     * text[])} 를 직접 확인했고, {@code EXPLAIN} 이 두 인덱스 모두 {@code Custom Scan (ParadeDB Base Scan)} 을 타는 것을 확인했다 (2026-09-09
     * 실측).
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
            expanded_tokens AS (
                SELECT string_to_array(:expandedTokens, ' ') AS tokens
            ),
            text_hits AS (
                SELECT s.scene_id, s.clip_id, paradedb.score(s) AS score
                FROM npick.scene s
                JOIN npick.clip c ON c.clip_id = s.clip_id
                    AND c.active_pipeline_run_id = s.pipeline_run_id
                    AND c.deleted_at IS NULL
                WHERE s @@@ paradedb.boolean(should => ARRAY[
                    paradedb.boost(CAST(:captionWeight AS real),
                        paradedb.term_set('caption_tokens', (SELECT tokens FROM query_tokens))),
                    paradedb.boost(CAST(:transcriptWeight AS real),
                        paradedb.term_set('transcript_tokens', (SELECT tokens FROM query_tokens))),
                    paradedb.boost(CAST(:expandedWeight AS real),
                        paradedb.term_set('caption_tokens', (SELECT tokens FROM expanded_tokens))),
                    paradedb.boost(CAST(:expandedWeight AS real),
                        paradedb.term_set('transcript_tokens', (SELECT tokens FROM expanded_tokens)))])
            ),
            ocr_hits AS (
                SELECT k.scene_id, s.clip_id, max(paradedb.score(o)) AS score
                FROM npick.ocr_observation o
                JOIN npick.keyframe k ON k.keyframe_id = o.keyframe_id
                JOIN npick.scene s ON s.scene_id = k.scene_id
                JOIN npick.clip c ON c.clip_id = s.clip_id
                    AND c.active_pipeline_run_id = s.pipeline_run_id
                    AND c.deleted_at IS NULL
                WHERE o @@@ paradedb.term_set('tokens', (SELECT tokens FROM query_tokens))
                GROUP BY k.scene_id, s.clip_id
            )
            SELECT scene_id, clip_id, text_score, ocr_score, total
            FROM (
                SELECT coalesce(t.scene_id, o.scene_id) AS scene_id,
                       coalesce(t.clip_id, o.clip_id) AS clip_id,
                       coalesce(t.score, 0) AS text_score,
                       CAST(:ocrWeight AS real) * coalesce(o.score, 0) AS ocr_score,
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
    public List<SceneCandidateResult> findByWords(List<String> searchTokens, List<String> expandedTokens) {
        String tokens = joinTokens(searchTokens);
        String expanded = joinTokens(expandedTokens);
        // 토큰이 없으면 DB 를 부르지 않는다. 빈 배열로 질의하면 pg_search 는 오류 없이 0건을 주는데,
        // 그것은 "검색어에 내용어가 없었다" 와 "색인에 없었다" 를 구분할 수 없게 만든다.
        if (tokens.isEmpty()) return List.of();

        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tokens", tokens)
                .addValue("captionWeight", properties.captionWeight())
                .addValue("transcriptWeight", properties.transcriptWeight())
                .addValue("expandedTokens", expanded)
                .addValue("expandedWeight", properties.expandedWeight())
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
        // null 은 빈 목록과 다르다. 조용히 빈 결과를 주면 호출부 배선 실수가 "검색 결과 없음" 으로 위장된다.
        Objects.requireNonNull(searchTokens, "searchTokens");
        StringBuilder joined = new StringBuilder();
        for (String token : searchTokens) {
            if (token == null || token.isBlank()) continue;
            if (token.codePoints().anyMatch(Character::isWhitespace)) {
                throw new BusinessException(SceneCandidateErrorCode.SEARCH_TOKEN_CONTAINS_WHITESPACE);
            }
            if (!joined.isEmpty()) joined.append(' ');
            joined.append(token);
        }
        return joined.toString();
    }
}
