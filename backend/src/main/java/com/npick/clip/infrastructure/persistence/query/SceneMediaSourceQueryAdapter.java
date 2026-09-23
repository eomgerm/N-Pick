package com.npick.clip.infrastructure.persistence.query;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Component;

import com.npick.clip.application.query.media.SceneMediaSource;
import com.npick.clip.application.query.media.SceneMediaSourceQueryPort;

/** 장면 다운로드용 세 칸 projection. scene aggregate를 적재하지 않는다. */
@Component
public class SceneMediaSourceQueryAdapter implements SceneMediaSourceQueryPort {

    private static final String SQL = """
            SELECT c.storage_key, s.start_time_ms, s.end_time_ms
            FROM npick.scene s
            JOIN npick.clip c ON c.clip_id = s.clip_id AND c.deleted_at IS NULL
            WHERE s.scene_id = :sceneId
            """;

    private final EntityManager entityManager;

    public SceneMediaSourceQueryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<SceneMediaSource> findDownloadSource(long sceneId) {
        List<Object[]> rows = entityManager
                .createNativeQuery(SQL)
                .setParameter("sceneId", sceneId)
                .setMaxResults(1)
                .getResultList();
        return rows.stream()
                .findFirst()
                .map(row -> new SceneMediaSource(
                        (String) row[0], ((Number) row[1]).longValue(), ((Number) row[2]).longValue()));
    }
}
