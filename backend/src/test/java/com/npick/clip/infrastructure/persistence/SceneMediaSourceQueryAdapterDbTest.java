package com.npick.clip.infrastructure.persistence;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.npick.clip.application.query.media.SceneMediaSource;
import com.npick.clip.infrastructure.persistence.query.SceneMediaSourceQueryAdapter;
import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SceneMediaSourceQueryAdapter.class)
@Transactional
class SceneMediaSourceQueryAdapterDbTest {

    @Autowired
    SceneMediaSourceQueryAdapter adapter;

    @Autowired
    EntityManager em;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        NpickPostgres.datasource(properties);
    }

    @BeforeEach
    void seed() {
        exec("""
                INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at)
                VALUES (7501, 'scene-download-test', 'hash', '장면다운로드', 'reviewer', now(), now())
                """);
        exec("""
                INSERT INTO npick.clip (clip_id, source_type, storage_key, content_hash, transcript_source,
                    registered_by_id, created_at, updated_at, deleted_at)
                VALUES (7511, 'broadcast', 'clips/7511/original.mp4', repeat('a', 64), 'none', 7501, now(), now(), NULL),
                       (7512, 'broadcast', 'clips/7512/original.mp4', repeat('b', 64), 'none', 7501, now(), now(), now())
                """);
        exec("""
                INSERT INTO npick.pipeline_run (pipeline_run_id, clip_id, processing_no, pipeline_version, status,
                    stage_states_json, created_at, updated_at)
                VALUES (7521, 7511, 1, 'test-v1', 'succeeded', '{}'::jsonb, now(), now()),
                       (7522, 7512, 1, 'test-v1', 'succeeded', '{}'::jsonb, now(), now())
                """);
        exec("""
                INSERT INTO npick.scene (scene_id, clip_id, pipeline_run_id, start_time_ms, end_time_ms, shot_type,
                    created_at, updated_at)
                VALUES (7531, 7511, 7521, 1250, 2700, 'b_roll', now(), now()),
                       (7532, 7512, 7522, 0, 1000, 'b_roll', now(), now())
                """);
    }

    @Test
    void returnsOnlyTheStorageKeyAndStoredSceneBoundary() {
        assertThat(adapter.findDownloadSource(7531))
                .contains(new SceneMediaSource("clips/7511/original.mp4", 1_250, 2_700));
    }

    @Test
    void hidesUnknownScenesAndScenesOfDeletedClips() {
        assertThat(adapter.findDownloadSource(999_999)).isEmpty();
        assertThat(adapter.findDownloadSource(7532)).isEmpty();
    }

    private void exec(String sql) {
        em.createNativeQuery(sql).executeUpdate();
    }
}
