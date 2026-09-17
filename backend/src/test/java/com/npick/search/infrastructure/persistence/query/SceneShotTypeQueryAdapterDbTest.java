package com.npick.search.infrastructure.persistence.query;

import java.util.List;
import java.util.Map;
import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.npick.search.application.query.soft.FindSceneShotTypesQueryPort;
import com.npick.search.domain.model.ShotType;
import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;

// 실 PostgreSQL 대상. 보조 랭킹의 scene.shot_type 조회(-55, F-04) 통합 테스트.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SceneShotTypeQueryAdapter.class)
class SceneShotTypeQueryAdapterDbTest {

    @Autowired
    private FindSceneShotTypesQueryPort port;

    @Autowired
    private EntityManager em;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        NpickPostgres.datasource(properties);
    }

    @Test
    @DisplayName("닫힌 4값을 모두 읽는다")
    void readsEveryShotType() {
        seedClip();
        seedScene(9301, "anchor");
        seedScene(9302, "interview");
        seedScene(9303, "b_roll");
        seedScene(9304, "unknown");

        assertThat(port.find(List.of(9301L, 9302L, 9303L, 9304L)))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        9301L, ShotType.ANCHOR,
                        9302L, ShotType.INTERVIEW,
                        9303L, ShotType.B_ROLL,
                        9304L, ShotType.UNKNOWN));
    }

    @Test
    @DisplayName("없는 장면은 키가 없다 — 호출자가 가점 없음으로 다룬다")
    void missingScenesAreAbsentFromTheResult() {
        seedClip();
        seedScene(9301, "b_roll");

        assertThat(port.find(List.of(9301L, 9999L))).containsOnlyKeys(9301L);
    }

    @Test
    @DisplayName("빈 입력은 조회하지 않는다")
    void emptyInputIsNotQueried() {
        assertThat(port.find(List.of())).isEmpty();
    }

    /**
     * 4값 밖의 값은 가점 없음으로 떨어진다. 검색을 실패시키지 않는다.
     *
     * <p>던지면 파이프라인이 넣은 값 하나 때문에 후보 수백 건짜리 질의 전체가 500 이 된다. B-roll 가점은 동점 구간만 가르는 보조 신호인데 그것 때문에 관련성 높은 결과가 전부 사라지는
     * 것은 {@code SoftRankingResult} 가 선언한 「soft 는 후보를 버리지 않는다」를 가장 크게 깨는 경로다. 없는 장면과 같은 규칙(키 없음)으로 떨어뜨리고 이상값은 로그로 남긴다.
     */
    @Test
    @DisplayName("4값 밖의 값은 검색을 실패시키지 않고 가점 없음으로 떨어진다")
    void anUnexpectedStoredValueIsDroppedInsteadOfFailingTheSearch() {
        seedClip();
        seedScene(9301, "montage");
        seedScene(9302, "b_roll");

        // 이상값이 섞여 있어도 던지지 않고, 같은 조회의 정상 장면은 그대로 읽힌다.
        assertThat(port.find(List.of(9301L, 9302L))).containsExactlyInAnyOrderEntriesOf(Map.of(9302L, ShotType.B_ROLL));
    }

    private void seedClip() {
        exec("INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at)"
                + " VALUES (9001, 'editor-9001', 'hash', '편집기자', 'editor', now(), now())");
        exec("INSERT INTO npick.clip (clip_id, source_type, storage_key, content_hash, transcript_source,"
                + " registered_by_id, created_at, updated_at) VALUES (9101, 'broadcast', 'clips/9101/o',"
                + " repeat('a', 64), 'none', 9001, now(), now())");
        exec("INSERT INTO npick.pipeline_run (pipeline_run_id, clip_id, processing_no, pipeline_version, status,"
                + " stage_states_json, created_at, updated_at) VALUES (9201, 9101, 1, 'v1', 'succeeded',"
                + " '{}'::jsonb, now(), now())");
        exec("UPDATE npick.clip SET active_pipeline_run_id = 9201 WHERE clip_id = 9101");
    }

    private void seedScene(long sceneId, String shotType) {
        exec("INSERT INTO npick.scene (scene_id, clip_id, pipeline_run_id, start_time_ms, end_time_ms, shot_type,"
                + " created_at, updated_at) VALUES (" + sceneId + ", 9101, 9201, 0, 1000, '" + shotType
                + "', now(), now())");
    }

    private void exec(String sql) {
        em.createNativeQuery(sql).executeUpdate();
    }
}
