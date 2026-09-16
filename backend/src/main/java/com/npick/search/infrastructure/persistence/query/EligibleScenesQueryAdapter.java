package com.npick.search.infrastructure.persistence.query;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.npick.search.application.query.structured.FindEligibleScenesQueryPort;
import com.npick.search.application.query.structured.StructuredScoresResult;
import com.npick.search.domain.model.IneligibleReason;

@Repository
class EligibleScenesQueryAdapter implements FindEligibleScenesQueryPort {
    private final NamedParameterJdbcTemplate jdbc;

    EligibleScenesQueryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Eligibility find(Collection<Long> sceneIds) {
        Objects.requireNonNull(sceneIds, "sceneIds");
        if (sceneIds.isEmpty()) return new Eligibility(List.of(), List.of());
        var eligible = new ArrayList<EligibleScene>();
        var found = new HashSet<Long>();
        // sceneId 오름차순을 유지하면서 없는 장면을 조회 결과와 합친다. List 기반 O(n²) 차집합은 피한다.
        var ineligible = new TreeMap<Long, IneligibleReason>();
        // 배열 바인딩으로 태그 후보의 무제한 조회가 PostgreSQL 파라미터 개수 상한에 걸리지 않게 한다.
        // scene.clip_id는 NOT NULL FK라 클립 조인은 항상 성립한다. 삭제·활성 처리 판정만 행에서 읽는다.
        jdbc.query("""
                SELECT s.scene_id, s.clip_id, c.deleted_at IS NOT NULL AS clip_deleted,
                    c.active_pipeline_run_id IS DISTINCT FROM s.pipeline_run_id AS inactive_run
                FROM npick.scene s
                JOIN npick.clip c ON c.clip_id = s.clip_id
                WHERE s.scene_id = ANY(:sceneIds)
                ORDER BY s.scene_id
                """, new MapSqlParameterSource("sceneIds", sceneIds.toArray(Long[]::new)), row -> {
            long sceneId = row.getLong("scene_id");
            found.add(sceneId);
            // 삭제와 비활성 처리가 겹치면 클립 삭제를 남긴다. 클립이 사라지면 처리 선택은 의미가 없다.
            if (row.getBoolean("clip_deleted")) ineligible.put(sceneId, IneligibleReason.CLIP_DELETED);
            else if (row.getBoolean("inactive_run")) ineligible.put(sceneId, IneligibleReason.INACTIVE_RUN);
            else eligible.add(new EligibleScene(sceneId, row.getLong("clip_id")));
        });
        for (Long sceneId : sceneIds) {
            if (sceneId != null && !found.contains(sceneId)) {
                ineligible.put(sceneId, IneligibleReason.SCENE_NOT_FOUND);
            }
        }
        return new Eligibility(
                eligible,
                ineligible.entrySet().stream()
                        .map(entry -> new StructuredScoresResult.Ineligible(entry.getKey(), entry.getValue()))
                        .toList());
    }
}
