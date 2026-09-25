package com.npick.search.infrastructure.persistence.query;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
     * <p>{@code paradedb.score(<테이블 별칭>)} 는 공개 문서에 없는 형태다. 0.25.6 컨테이너의 {@code pg_proc} 에서
     * {@code score(relation_reference anyelement)} 를 직접 확인했고, {@code EXPLAIN} 이 두 인덱스 모두 {@code Custom Scan (ParadeDB
     * Base Scan)} 을 타는 것을 확인했다 (2026-09-09 실측).
     *
     * <p><b>{@code term_set} 이 아니라 토큰마다 {@code term} 을 {@code should} 로 건다</b> (S15P21A501-320). 0.25.6 의
     * {@code term_set} 은 맞은 문서에 <b>상수 점수 1.0</b> 을 준다 — 몇 개의 토큰이 맞았는지도, 토큰이 얼마나 드문지도, 문서 길이도 보지 않는다. 그래서 순위가 「몇
     * 칸(캡션·대사·화면 글자)에서 맞았나」 1~3 점과 {@code scene_id} 로만 정해져, 세 토큰이 다 맞은 장면이 흔한 한 토큰만 맞은 장면 수백 개 뒤로 밀렸다 (로컬 운영 복원본 실측:
     * 「화재 현장 소방관」 세 토큰 일치 장면의 중앙 순위 359위). 토큰별 {@code term} 의 {@code should} 는 <b>맞는 문서 집합이 {@code term_set} 과
     * 같고</b>(OR) 점수만 실제 BM25(IDF·TF·길이 정규화의 합)가 된다. 토큰은 바인딩 한 개({@code :tokens})로 넘기고 {@code unnest} 로 절을 만든다 — 토큰 값이
     * SQL 문자열에 들어가지 않는다.
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
                    AND c.deleted_at IS NULL
                WHERE s @@@ paradedb.boolean(should => ARRAY[
                    paradedb.boost(CAST(:captionWeight AS real), paradedb.boolean(should => ARRAY(
                        SELECT paradedb.term('caption_tokens', token)
                        FROM unnest((SELECT tokens FROM query_tokens)) AS token))),
                    paradedb.boost(CAST(:transcriptWeight AS real), paradedb.boolean(should => ARRAY(
                        SELECT paradedb.term('transcript_tokens', token)
                        FROM unnest((SELECT tokens FROM query_tokens)) AS token)))%s])
            ),
            ocr_hits AS (
                SELECT k.scene_id, s.clip_id, max(paradedb.score(o)) AS score
                FROM npick.ocr_observation o
                JOIN npick.keyframe k ON k.keyframe_id = o.keyframe_id
                JOIN npick.scene s ON s.scene_id = k.scene_id
                JOIN npick.clip c ON c.clip_id = s.clip_id
                    AND c.active_pipeline_run_id = s.pipeline_run_id
                    AND c.deleted_at IS NULL
                WHERE o @@@ paradedb.boolean(should => ARRAY(
                    SELECT paradedb.term('tokens', token)
                    FROM unnest((SELECT tokens FROM query_tokens)) AS token))
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

    /**
     * 확장어 한 구의 절. 구 안은 {@code must}, 구 사이는 바깥 {@code should} 가 OR 로 묶는다 (S15P21A501-302).
     *
     * <p>캡션과 대사를 <b>따로</b> 건다. 한 구는 한 덩이의 말이므로 같은 칸 안에서 모두 맞아야 한다 — 칸을 넘나들며 AND 를 걸면 캡션의 {@code 중국} 과 대사의 {@code 음식} 이
     * 맞아 중국 경제 뉴스가 다시 올라온다. 확장어는 필드 가중치가 아니라 한 가중치({@code expandedWeight})로만 다룬다.
     *
     * <p>{@code term_set} 이 아니라 {@code term} 인 것이 이 티켓의 전부다. {@code term_set} 은 배열을 OR 로 받는다.
     */
    private static final String EXPANDED_PHRASE_CLAUSES = """
            ,
                    paradedb.boost(CAST(:expandedWeight AS real), paradedb.boolean(must => ARRAY[%1$s])),
                    paradedb.boost(CAST(:expandedWeight AS real), paradedb.boolean(must => ARRAY[%2$s]))\
            """;

    private static final Logger log = LoggerFactory.getLogger(WordSceneCandidateAdapter.class);

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final SceneCandidateProperties properties;

    WordSceneCandidateAdapter(NamedParameterJdbcTemplate jdbcTemplate, SceneCandidateProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    @Override
    public List<SceneCandidateResult> findByWords(List<String> searchTokens, List<List<String>> expandedPhrases) {
        String tokens = joinTokens(searchTokens);
        // null 은 빈 목록과 다르다. 조용히 빈 결과를 주면 호출부 배선 실수가 "검색 결과 없음" 으로 위장된다.
        Objects.requireNonNull(expandedPhrases, "expandedPhrases");
        // 토큰이 없으면 DB 를 부르지 않는다. 빈 배열로 질의하면 pg_search 는 오류 없이 0건을 주는데,
        // 그것은 "검색어에 내용어가 없었다" 와 "색인에 없었다" 를 구분할 수 없게 만든다.
        if (tokens.isEmpty()) return List.of();

        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tokens", tokens)
                .addValue("captionWeight", properties.captionWeight())
                .addValue("transcriptWeight", properties.transcriptWeight())
                .addValue("expandedWeight", properties.expandedWeight())
                .addValue("ocrWeight", properties.ocrWeight())
                .addValue("poolSize", properties.poolSize());

        return jdbcTemplate.query(
                FIND_CANDIDATES_SQL.formatted(expandedClauses(usablePhrases(expandedPhrases), parameters)),
                parameters,
                (row, rowNumber) -> new SceneCandidateResult(
                        row.getLong("scene_id"),
                        row.getLong("clip_id"),
                        row.getDouble("total"),
                        row.getDouble("text_score"),
                        row.getDouble("ocr_score")));
    }

    /**
     * 조회에 쓸 수 있는 확장어 구만 남긴다 — 빈 구와 같은 구의 중복을 뺀다.
     *
     * <p><b>여기서는 던지지 않는다</b> (S15P21A501-48 계약 9 「확장어 부재·토큰화 실패는 degraded 가 아니다」). 확장어는 보조 신호이고, 한 건이 이상하다고 원 질의 검색까지
     * 끊으면 원 질의로 충분히 찾을 수 있던 결과까지 잃는다. 원 질의 토큰은 반대다 — 그쪽은 우리 토크나이저가 만든 값이라 규약 위반이면 {@link #joinTokens} 가 거부한다.
     *
     * <p>같은 구의 중복을 빼는 이유는 한 구가 두 절에서 가산되면 F-05 의 「같은 개체를 중복 계산하지 않는다」를 깨기 때문이다.
     */
    private static List<List<String>> usablePhrases(List<List<String>> expandedPhrases) {
        Set<List<String>> phrases = new LinkedHashSet<>();
        for (List<String> phrase : expandedPhrases) {
            List<String> usable = usablePhrase(phrase);
            if (usable != null && !usable.isEmpty()) phrases.add(usable);
        }
        return List.copyOf(phrases);
    }

    /**
     * 구 하나를 검사한다. 쓸 수 없는 토큰이 하나라도 있으면 <b>그 구를 통째로 버린다</b>.
     *
     * <p>토큰만 빼고 남은 것으로 {@code must} 를 걸면 구가 그만큼 헐거워진다 — 「중국 음식」에서 한쪽이 빠지면 {@code 중국} 단독 매칭이 되어 이 티켓이 없애려던 넓은 매칭이 그대로
     * 되살아난다. 확장어 하나를 통째로 잃는 비용보다 헐거워진 구가 무관한 후보를 끌어오는 비용이 크다. 문서빈도 컷을 뺀 것과 같은 판단이며 (S15P21A501-302), 출처가 「흔한 토큰」이 아니라
     * 「쓸 수 없는 토큰」일 뿐이다.
     *
     * @return 쓸 수 있는 토큰 목록(중복 제거). 구를 버려야 하면 {@code null}
     */
    private static List<String> usablePhrase(List<String> phrase) {
        if (phrase == null) {
            log.warn("확장어 구가 null 이다. 그 구 없이 검색을 이어간다");
            return null;
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : phrase) {
            if (token == null || token.isBlank()) {
                log.warn("확장어 구에 빈 토큰이 있다. 그 구 없이 검색을 이어간다");
                return null;
            }
            if (token.codePoints().anyMatch(Character::isWhitespace)) {
                // 공백이 있으면 DB 에서 두 토큰으로 쪼개져 구의 의미가 조용히 달라진다.
                log.warn("확장어 구에 공백이 든 토큰이 있다. 그 구 없이 검색을 이어간다");
                return null;
            }
            tokens.add(token);
        }
        return List.copyOf(tokens);
    }

    /** 구마다 캡션 {@code must} 절과 대사 {@code must} 절을 하나씩 만든다. 구가 없으면 확장어 절 자체가 없다. */
    private static String expandedClauses(List<List<String>> phrases, MapSqlParameterSource parameters) {
        StringBuilder clauses = new StringBuilder();
        for (int phrase = 0; phrase < phrases.size(); phrase++) {
            StringBuilder caption = new StringBuilder();
            StringBuilder transcript = new StringBuilder();
            List<String> tokens = phrases.get(phrase);
            for (int token = 0; token < tokens.size(); token++) {
                String name = "e" + phrase + "_" + token;
                parameters.addValue(name, tokens.get(token));
                if (token > 0) {
                    caption.append(", ");
                    transcript.append(", ");
                }
                caption.append("paradedb.term('caption_tokens', CAST(:")
                        .append(name)
                        .append(" AS text))");
                transcript
                        .append("paradedb.term('transcript_tokens', CAST(:")
                        .append(name)
                        .append(" AS text))");
            }
            clauses.append(EXPANDED_PHRASE_CLAUSES.formatted(caption, transcript));
        }
        return clauses.toString();
    }

    /**
     * 토큰을 공백으로 이어 SQL 의 {@code string_to_array} 에 넘긴다.
     *
     * <p>토큰 하나에 공백이 있으면 DB 에서 두 토큰으로 쪼개져 검색어가 조용히 달라진다. Kiwi 형태소는 공백을 품지 않으므로 이런 값은 규약 위반이며, 결과를 왜곡하는 대신 거부한다.
     *
     * <p>같은 토큰은 한 번만 넘긴다. {@code term_set} 은 집합이라 중복이 점수를 바꾸지 않았지만 토큰별 {@code term} 의 {@code should} 는 같은 절이 두 번 가산된다.
     */
    private static String joinTokens(List<String> searchTokens) {
        // null 은 빈 목록과 다르다. 조용히 빈 결과를 주면 호출부 배선 실수가 "검색 결과 없음" 으로 위장된다.
        Objects.requireNonNull(searchTokens, "searchTokens");
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : searchTokens) {
            if (token == null || token.isBlank()) continue;
            if (token.codePoints().anyMatch(Character::isWhitespace)) {
                throw new BusinessException(SceneCandidateErrorCode.SEARCH_TOKEN_CONTAINS_WHITESPACE);
            }
            tokens.add(token);
        }
        return String.join(" ", tokens);
    }
}
