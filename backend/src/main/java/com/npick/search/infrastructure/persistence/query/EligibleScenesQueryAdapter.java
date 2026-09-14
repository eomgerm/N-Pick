package com.npick.search.infrastructure.persistence.query;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.npick.search.application.query.structured.FindEligibleScenesQueryPort;

@Repository
class EligibleScenesQueryAdapter implements FindEligibleScenesQueryPort {
    private final NamedParameterJdbcTemplate jdbc;

    EligibleScenesQueryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<EligibleScene> find(Collection<Long> sceneIds) {
        Objects.requireNonNull(sceneIds, "sceneIds");
        if (sceneIds.isEmpty()) return List.of();
        // 배열 바인딩으로 태그 후보의 무제한 조회가 PostgreSQL 파라미터 개수 상한에 걸리지 않게 한다.
        return jdbc.query(
                """
                SELECT s.scene_id, s.clip_id
                FROM npick.scene s
                JOIN npick.clip c ON c.clip_id = s.clip_id
                    AND c.active_pipeline_run_id = s.pipeline_run_id
                    AND c.deleted_at IS NULL
                WHERE s.scene_id = ANY(:sceneIds)
                ORDER BY s.scene_id
                """,
                new MapSqlParameterSource("sceneIds", sceneIds.toArray(Long[]::new)),
                (row, number) -> new EligibleScene(row.getLong("scene_id"), row.getLong("clip_id")));
    }
}
