package com.npick.tag.infrastructure.persistence.query;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.npick.tag.application.query.FindTagJudgmentsQueryPort;
import com.npick.tag.application.query.TagCondition;
import com.npick.tag.domain.model.TagJudgment;
import com.npick.tag.domain.model.TagType;

/**
 * 태그 근거 줄을 긁어온다 (S15P21A501-161).
 *
 * <p>JPA Entity 도 Spring Data Repository 도 만들지 않는다. 돌려주는 것이 Aggregate 가 아니라 Projection 이고(설계 정본 §9), 이 조회만을 위해
 * {@code TaggingJpaEntity} 를 만드는 것은 정본 §17 이 금지한 보일러플레이트다.
 *
 * <p>{@link NamedParameterJdbcTemplate} 은 Spring 이 관리하는 {@code DataSource} 를 통해 주변 트랜잭션의 커넥션을 쓴다. 후보 검증 검색(F-12)이 한
 * 트랜잭션 안에서 후보를 적용하고 검색한 뒤 되돌리는 방식이라(FRD §11) 이 성질이 계약의 일부다. (검수자 태그 교정 후보는 S15P21A501-160 이후 {@code confirmed=false} 로
 * 저장되고, 아래 조인의 {@code e.confirmed} 가 그 후보를 일반 검색에서 제외한다.)
 */
@Repository
class TagJudgmentQueryAdapter implements FindTagJudgmentsQueryPort {

    /**
     * 스키마를 명시하는 이유: JDBC 연결의 기본 {@code search_path} 에 {@code npick} 이 없다. Hibernate 는 {@code hibernate.default_schema}
     * 로 스스로 붙이지만 이 SQL 은 직접 붙여야 한다.
     *
     * <p><b>장면 조인 한 줄이 상속이다.</b> {@code tg.scene_id IS NULL} 인 클립 전체 태그는 그 클립의 장면마다 한 줄로 펼쳐지고, 값이 있으면 그 장면에만 붙는다 (F-04
     * "영상 전체 태그는 해당 영상의 장면에 상속된다"). 판정기는 이 펼쳐진 결과를 {@code (scene_id, tag_id)} 로 묶기만 한다.
     *
     * <p><b>클립 조인 두 조건이 검색 대상을 정한다.</b> FRD §6.1 — 「영상의 검색 가능 여부는 현재 제공 중인 {@code active_pipeline_run_id} 와 논리 삭제 여부로
     * 판단한다」. 앞이 세대 격리고(재처리하면 같은 클립에 새 {@code pipeline_run} 과 새 장면이 생긴다) 뒤가 논리 삭제다. 단어 검색보다 여기서 더 위험하다 — 클립 태그 하나가 그 클립의
     * 장면 <b>전부</b> 를 끌어올리므로, 빼면 삭제된 영상의 장면이 태그 하나로 통째로 올라온다.
     *
     * <p>{@code tag_evidence} 를 {@code INNER JOIN} 으로 붙인다. 근거가 하나도 없는 태깅은 어차피 유효하지 않으므로(F-04 "값만 저장하지 않고 출처와 확인 가능한 근거
     * 위치를 연결한다") 줄을 만들 필요가 없다.
     *
     * <p><b>{@code e.confirmed} 로 좁힌다.</b> 검수자 교정 후보({@code confirmed=false}, S15P21A501-160)는 확정(-84) 전까지 검색·해석에 반영되면
     * 안 된다(F-08/F-12 "신고·후보만으로 태그를 바꾸지 않는다"). 확정된 근거만 우선순위 판정에 들어간다.
     *
     * <p>정렬하지 않는다. 최신 판단 고르기는 판정기가 한다. {@code ix_evidence_tagging_latest} 는 여기서도 {@code (tagging_id, ...)} 접근에 그대로 쓰인다.
     */
    private static final String SELECT_JUDGMENTS = """
            SELECT s.scene_id, s.clip_id, t.tag_id, t.tag_type, t.match_value, t.name,
                   (tg.scene_id IS NOT NULL) AS scene_scoped,
                   e.source, e.verification_status, e.created_at, e.evidence_id
            FROM npick.tagging tg
            JOIN npick.tag t ON t.tag_id = tg.tag_id
            JOIN npick.scene s ON s.clip_id = tg.clip_id
                AND (tg.scene_id IS NULL OR tg.scene_id = s.scene_id)
            JOIN npick.clip c ON c.clip_id = s.clip_id AND c.deleted_at IS NULL
            JOIN npick.tag_evidence e ON e.tagging_id = tg.tagging_id AND e.confirmed
            WHERE""";

    private static final String ACTIVE_SCENE = "c.active_pipeline_run_id = s.pipeline_run_id AND ";

    private static final RowMapper<TagJudgment> ROW_MAPPER = (row, rowNumber) -> new TagJudgment(
            row.getLong("scene_id"),
            row.getLong("clip_id"),
            row.getLong("tag_id"),
            // 11종에 없는 값이면 여기서 5xx 로 실패한다. tag_type 에는 값을 제한하는 CHECK 가 없어
            // 실제로 가능한 상황이고, 조용히 버리면 태그가 검색에서 사라지는데 신호가 남지 않는다.
            //
            // 대가는 알고 고른 것이다. 이 판정은 명시 필터 비교·구조화 축 점수·근거 설명이 모두 지나는
            // 길이라, 잘못 쓰인 한 행이 그 장면들을 건드리는 검색 전체를 죽인다. 두 방향이 비대칭이라는
            // 것도 남긴다 — findByConditions 는 알려진 11종으로 tag_type 을 걸러 조회하므로 같은 행에
            // 걸리지 않고, findByScenes 만 걸린다. 근본 해결은 스키마에 CHECK 를 넣는 것이고 별 일감이다.
            TagType.from(row.getString("tag_type")),
            row.getString("match_value"),
            row.getString("name"),
            row.getBoolean("scene_scoped"),
            row.getString("source"),
            row.getString("verification_status"),
            row.getTimestamp("created_at").toInstant(),
            row.getLong("evidence_id"));

    private final NamedParameterJdbcTemplate jdbcTemplate;

    TagJudgmentQueryAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<TagJudgment> findByScenes(Collection<Long> sceneIds) {
        Objects.requireNonNull(sceneIds, "sceneIds");
        if (sceneIds.isEmpty()) return List.of();

        // IN (:sceneIds) 이 아니라 = ANY(배열) 이다. 앞의 형태는 id 하나당 바인딩 파라미터 하나로 펼쳐지는데,
        // PostgreSQL 확장 질의 프로토콜의 파라미터 상한은 65535(int16) 다. 태그 채널은 후보 개수를 일부러
        // 제한하지 않으므로(FindTagMatchedScenesUseCase) 그 후보가 그대로 넘어오면 상한을 넘길 수 있고,
        // 그러면 태그가 빠지는 것이 아니라 질의가 죽는다. 배열은 파라미터 하나이고 실행 계획도 같다.
        return query(
                ACTIVE_SCENE + "s.scene_id = ANY(:sceneIds)",
                new MapSqlParameterSource("sceneIds", sceneIds.toArray(Long[]::new)));
    }

    @Override
    public List<TagJudgment> findByRunScenes(long clipId, long pipelineRunId, Collection<Long> sceneIds) {
        Objects.requireNonNull(sceneIds, "sceneIds");
        if (sceneIds.isEmpty()) return List.of();
        return query(
                "c.clip_id = :clipId AND s.pipeline_run_id = :pipelineRunId " + "AND s.scene_id = ANY(:sceneIds)",
                new MapSqlParameterSource()
                        .addValue("clipId", clipId)
                        .addValue("pipelineRunId", pipelineRunId)
                        .addValue("sceneIds", sceneIds.toArray(Long[]::new)));
    }

    @Override
    public List<TagJudgment> findByConditions(List<TagCondition> conditions) {
        Objects.requireNonNull(conditions, "conditions");
        if (conditions.isEmpty()) return List.of();

        // 조건 수만큼 번호 붙인 파라미터를 만든다. 값은 전부 바인딩되고 SQL 에 끼워 넣는 것은 번호뿐이다.
        // 조건은 리졸버 출력(개체 축, 한 자릿수)에 키워드 조건(상한 conditionCap, 기본 12)이 더해진 것이라
        // 수십 개를 넘지 않는다. 몇 개든 OR 로 묶은 한 SQL 이다.
        StringBuilder predicate = new StringBuilder();
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        for (int index = 0; index < conditions.size(); index++) {
            TagCondition condition = conditions.get(index);
            if (index > 0) predicate.append(" OR ");
            if (condition.ignoreCase()) {
                // 키워드 조건 (S15P21A501-321). 두 끝이 같다는 것은 TagCondition 이 보장한다.
                // lower() 라 uq_tag_type_match_value 의 match_value 부분은 타지 않고 tag_type 까지만 탄다.
                predicate
                        .append("(t.tag_type = :type")
                        .append(index)
                        .append(" AND lower(t.match_value) = lower(:from")
                        .append(index)
                        .append("))");
            } else {
                predicate
                        .append("(t.tag_type = :type")
                        .append(index)
                        .append(" AND t.match_value BETWEEN :from")
                        .append(index)
                        .append(" AND :to")
                        .append(index)
                        .append(')');
                parameters.addValue("to" + index, condition.toInclusive());
            }
            parameters.addValue("type" + index, condition.type().storedValue());
            parameters.addValue("from" + index, condition.fromInclusive());
        }

        return query(ACTIVE_SCENE + "(" + predicate + ")", parameters);
    }

    /**
     * 조건을 붙이는 유일한 자리.
     *
     * <p>공백을 여기서 넣는다. 텍스트 블록은 줄 끝 공백을 지우므로 {@code SELECT_JUDGMENTS} 는 {@code WHERE} 로 끝난다 — 호출부에서 이어 붙이면
     * {@code WHEREs.scene_id} 가 된다. 조건이 괄호로 시작하는 경우에만 우연히 문법이 맞아 한쪽 방향만 깨지는 함정이었다 (2026-09-09 실측).
     */
    private List<TagJudgment> query(String condition, MapSqlParameterSource parameters) {
        return jdbcTemplate.query(SELECT_JUDGMENTS + " " + condition, parameters, ROW_MAPPER);
    }
}
