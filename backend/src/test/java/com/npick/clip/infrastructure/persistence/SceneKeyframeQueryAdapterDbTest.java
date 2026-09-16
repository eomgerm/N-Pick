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

import com.npick.clip.application.query.thumbnail.SceneKeyframeSource;
import com.npick.clip.infrastructure.persistence.query.SceneKeyframeQueryAdapter;
import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;

// 실 PostgreSQL(paradedb) 대상 통합 테스트. Testcontainers 가 컨테이너를 띄운다(NpickPostgres).
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SceneKeyframeQueryAdapter.class)
@Transactional
class SceneKeyframeQueryAdapterDbTest {

    private static final long EDITOR_ID = 7001L;
    private static final long CLIP_ID = 7101L;
    private static final long DELETED_CLIP_ID = 7102L;
    private static final long OLD_RUN_ID = 7201L;
    private static final long ACTIVE_RUN_ID = 7202L;
    private static final long DELETED_CLIP_RUN_ID = 7203L;
    private static final long SCENE_WITH_KEYFRAMES = 7301L;
    private static final long SCENE_WITHOUT_KEYFRAMES = 7302L;
    private static final long SCENE_OF_OLD_RUN = 7303L;
    private static final long SCENE_OF_DELETED_CLIP = 7304L;

    @Autowired
    private SceneKeyframeQueryAdapter adapter;

    @Autowired
    private EntityManager em;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        NpickPostgres.datasource(properties);
    }

    @BeforeEach
    void seed() {
        exec("""
                INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at)
                VALUES (%d, 'editor-test-7001', 'hash', '편집기자7001', 'editor', now(), now())
                """.formatted(EDITOR_ID));
        exec("""
                INSERT INTO npick.clip (clip_id, source_type, storage_key, content_hash, transcript_source,
                    registered_by_id, created_at, updated_at, deleted_at)
                VALUES (%d, 'broadcast', 'clips/7101/original', repeat('a', 64), 'none', %d, now(), now(), NULL),
                       (%d, 'broadcast', 'clips/7102/original', repeat('b', 64), 'none', %d, now(), now(), now())
                """.formatted(CLIP_ID, EDITOR_ID, DELETED_CLIP_ID, EDITOR_ID));
        exec("""
                INSERT INTO npick.pipeline_run (pipeline_run_id, clip_id, processing_no, pipeline_version, status,
                    stage_states_json, created_at, updated_at)
                VALUES (%d, %d, 1, 'test-pipeline-v1', 'succeeded', '{}'::jsonb, now(), now()),
                       (%d, %d, 2, 'test-pipeline-v1', 'succeeded', '{}'::jsonb, now(), now()),
                       (%d, %d, 1, 'test-pipeline-v1', 'succeeded', '{}'::jsonb, now(), now())
                """.formatted(OLD_RUN_ID, CLIP_ID, ACTIVE_RUN_ID, CLIP_ID, DELETED_CLIP_RUN_ID, DELETED_CLIP_ID));
        exec("UPDATE npick.clip SET active_pipeline_run_id = %d WHERE clip_id = %d".formatted(ACTIVE_RUN_ID, CLIP_ID));
        exec("UPDATE npick.clip SET active_pipeline_run_id = %d WHERE clip_id = %d"
                .formatted(DELETED_CLIP_RUN_ID, DELETED_CLIP_ID));
        exec("""
                INSERT INTO npick.scene (scene_id, clip_id, pipeline_run_id, start_time_ms, end_time_ms, shot_type,
                    created_at, updated_at)
                VALUES (%d, %d, %d, 0, 10000, 'b_roll', now(), now()),
                       (%d, %d, %d, 10000, 20000, 'b_roll', now(), now()),
                       (%d, %d, %d, 0, 10000, 'b_roll', now(), now()),
                       (%d, %d, %d, 0, 10000, 'b_roll', now(), now())
                """.formatted(
                        SCENE_WITH_KEYFRAMES,
                        CLIP_ID,
                        ACTIVE_RUN_ID,
                        SCENE_WITHOUT_KEYFRAMES,
                        CLIP_ID,
                        ACTIVE_RUN_ID,
                        SCENE_OF_OLD_RUN,
                        CLIP_ID,
                        OLD_RUN_ID,
                        SCENE_OF_DELETED_CLIP,
                        DELETED_CLIP_ID,
                        DELETED_CLIP_RUN_ID));
        // 가장 이른 프레임을 마지막에 넣는다. 삽입 순서나 keyframe_id 순서로 고르면 이 표본에서 틀린다.
        exec("""
                INSERT INTO npick.keyframe (keyframe_id, scene_id, timestamp_ms, storage_key)
                VALUES (7401, %d, 5000, 'runs/7202/frames/s0000/kf-000005000.jpg'),
                       (7402, %d, 9000, 'runs/7202/frames/s0000/kf-000009000.jpg'),
                       (7403, %d, 1000, 'runs/7202/frames/s0000/kf-000001000.jpg'),
                       (7404, %d, 0, 'runs/7201/frames/s0000/kf-000000000.jpg'),
                       (7405, %d, 0, 'runs/7203/frames/s0000/kf-000000000.jpg')
                """.formatted(
                        SCENE_WITH_KEYFRAMES,
                        SCENE_WITH_KEYFRAMES,
                        SCENE_WITH_KEYFRAMES,
                        SCENE_OF_OLD_RUN,
                        SCENE_OF_DELETED_CLIP));
    }

    /** 대표는 timestamp_ms 가 가장 이른 프레임이다 (FRD F-03). */
    @Test
    void picksTheEarliestKeyframeOfTheScene() {
        SceneKeyframeSource source = adapter.findRepresentativeKeyframe(SCENE_WITH_KEYFRAMES);

        assertThat(source.sceneFound()).isTrue();
        assertThat(source.storageKey()).isEqualTo("runs/7202/frames/s0000/kf-000001000.jpg");
    }

    /** 장면은 있는데 프레임 추출이 아직 안 끝난 상태다. 없는 장면과 같은 답을 주면 화면이 안내를 고를 수 없다. */
    @Test
    void separatesASceneWithoutKeyframesFromAnUnknownScene() {
        assertThat(adapter.findRepresentativeKeyframe(SCENE_WITHOUT_KEYFRAMES))
                .isEqualTo(SceneKeyframeSource.keyframeMissing());
        assertThat(adapter.findRepresentativeKeyframe(999_999L)).isEqualTo(SceneKeyframeSource.sceneMissing());
    }

    /** 재처리로 활성 run 이 바뀌어도 옛 세대 장면의 이미지는 계속 준다. 검수 문의 큐가 접수 당시의 장면을 그대로 보여 주기 때문이다 — 여기서 막으면 이미 접수된 문의의 카드가 이미지를 잃는다. */
    @Test
    void stillServesScenesOfASupersededPipelineRun() {
        SceneKeyframeSource source = adapter.findRepresentativeKeyframe(SCENE_OF_OLD_RUN);

        assertThat(source.storageKey()).isEqualTo("runs/7201/frames/s0000/kf-000000000.jpg");
    }

    /** 논리 삭제한 영상의 프레임은 재생과 같은 이유로 더 내보내지 않는다. 없는 장면과 같은 응답을 준다. */
    @Test
    void hidesScenesOfALogicallyDeletedClip() {
        assertThat(adapter.findRepresentativeKeyframe(SCENE_OF_DELETED_CLIP))
                .isEqualTo(SceneKeyframeSource.sceneMissing());
    }

    private void exec(String sql) {
        em.createNativeQuery(sql).executeUpdate();
    }
}
