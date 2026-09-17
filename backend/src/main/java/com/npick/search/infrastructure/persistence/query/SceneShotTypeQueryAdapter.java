package com.npick.search.infrastructure.persistence.query;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.npick.search.application.query.soft.FindSceneShotTypesQueryPort;
import com.npick.search.domain.model.ShotType;

@Repository
class SceneShotTypeQueryAdapter implements FindSceneShotTypesQueryPort {
    private static final Logger log = LoggerFactory.getLogger(SceneShotTypeQueryAdapter.class);

    private final NamedParameterJdbcTemplate jdbc;

    SceneShotTypeQueryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<Long, ShotType> find(Collection<Long> sceneIds) {
        Objects.requireNonNull(sceneIds, "sceneIds");
        if (sceneIds.isEmpty()) return Map.of();
        var byScene = new HashMap<Long, ShotType>();
        var unknown = new ArrayList<String>();
        // 적격 판정은 -52 가 이미 했다. 여기서 클립·처리 상태를 다시 보면 같은 판정이 두 곳에 생긴다.
        // 배열 바인딩으로 후보 수가 PostgreSQL 파라미터 개수 상한에 걸리지 않게 한다 (EligibleScenesQueryAdapter 와 같은 이유).
        jdbc.query("""
                SELECT s.scene_id, s.shot_type
                FROM npick.scene s
                WHERE s.scene_id = ANY(:sceneIds)
                """, new MapSqlParameterSource("sceneIds", sceneIds.toArray(Long[]::new)), row -> {
            long sceneId = row.getLong("scene_id");
            String stored = row.getString("shot_type");
            // 던지면 이상값 하나가 질의 전체를 500 으로 만든다. 가점만 포기하고 이상은 로그로 드러낸다.
            ShotType.parse(stored).ifPresentOrElse(shotType -> byScene.put(sceneId, shotType), () -> unknown.add(stored));
        });
        // 조회당 한 줄이다. 장면마다 찍으면 원인은 데이터 하나인데 로그가 검색 QPS × pool-size 로 증폭된다.
        if (!unknown.isEmpty()) {
            log.warn("Dropped {} scene(s) with an unknown scene.shot_type: {}", unknown.size(), Set.copyOf(unknown));
        }
        return Map.copyOf(byScene);
    }
}
