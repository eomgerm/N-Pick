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
import org.springframework.transaction.annotation.Transactional;

import com.npick.search.application.query.exclusion.FindActiveSceneExclusionsQueryPort;
import com.npick.search.application.query.exclusion.FindActiveSceneExclusionsQueryPort.ActiveSceneExclusion;
import com.npick.search.domain.model.NormalizedSearch;
import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ActiveSceneExclusionQueryAdapter.class)
class ActiveSceneExclusionQueryAdapterDbTest {

    private static final NormalizedSearch SEARCH =
            NormalizedSearch.of("제주 불꽃놀이", Map.of("region", List.of("제주")), "v1");

    @Autowired
    private FindActiveSceneExclusionsQueryPort port;

    @Autowired
    private EntityManager em;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        NpickPostgres.datasource(properties);
    }

    @Test
    @Transactional
    @DisplayName("지문과 정규화 원값이 모두 같은 활성 장면 제외만 규칙 ID 순으로 읽는다")
    void readsOnlyActiveExactSceneExclusions() {
        seedContext();
        insertExcludeRule(6602, 9902, true, SEARCH.normalizedQuery(), "{\"region\":[\"제주\"]}", "v1");
        insertExcludeRule(6601, 9901, true, SEARCH.normalizedQuery(), "{\"region\":[\"제주\"]}", "v1");
        insertExcludeRule(6603, 9903, false, SEARCH.normalizedQuery(), "{\"region\":[\"제주\"]}", "v1");
        insertExcludeRule(6604, 9904, true, "부산 불꽃놀이", "{\"region\":[\"제주\"]}", "v1");
        insertExcludeRule(6605, 9905, true, SEARCH.normalizedQuery(), "{\"region\":[\"부산\"]}", "v1");
        insertPatchRule(6606, 9906);

        assertThat(port.find(SEARCH))
                .containsExactly(new ActiveSceneExclusion(6601, 9301), new ActiveSceneExclusion(6602, 9301));
    }

    private void seedContext() {
        for (long memberId = 9001; memberId <= 9006; memberId++) {
            exec("INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at)"
                    + " VALUES (" + memberId + ", 'member-" + memberId
                    + "', 'hash', '사용자', 'editor', now(), now())");
        }
        exec("INSERT INTO npick.clip (clip_id, source_type, storage_key, content_hash, transcript_source,"
                + " registered_by_id, created_at, updated_at) VALUES (9101, 'broadcast', 'clips/9101/o',"
                + " repeat('a', 64), 'none', 9001, now(), now())");
        exec("INSERT INTO npick.pipeline_run (pipeline_run_id, clip_id, processing_no, pipeline_version, status,"
                + " stage_states_json, created_at, updated_at) VALUES (9201, 9101, 3, 'v1', 'succeeded',"
                + " '{}'::jsonb, now(), now())");
        exec("INSERT INTO npick.scene (scene_id, clip_id, pipeline_run_id, start_time_ms, end_time_ms, shot_type,"
                + " created_at, updated_at) VALUES (9301, 9101, 9201, 42000, 49000, 'b_roll', now(), now())");
        exec("INSERT INTO npick.search_execution (search_execution_id, searched_by_id, query_text, normalized_query,"
                + " explicit_filters_json, normalized_filters_json, query_fingerprint, normalization_version,"
                + " execution_type, status, degraded_reasons_json, applied_excludes_json, search_config_json,"
                + " config_version, created_at, updated_at) VALUES (9701, 9001, 'q', 'q', '{}'::jsonb, '{}'::jsonb,"
                + " repeat('a', 64), 'v1', 'original', 'succeeded', '[]'::jsonb, '[]'::jsonb, '{}'::jsonb, 'cfg-v1',"
                + " now(), now())");
        exec("INSERT INTO npick.search_result (search_result_id, search_execution_id, scene_id, result_rank,"
                + " explain_json) VALUES (9801, 9701, 9301, 1, '{\"score\":1}'::jsonb)");
        for (long index = 1; index <= 6; index++) {
            exec("INSERT INTO npick.feedback (feedback_id, search_result_id, created_by_id, status, created_at,"
                    + " updated_at) VALUES (" + (9900 + index) + ", 9801, " + (9000 + index)
                    + ", 'PENDING', now(), now())");
        }
    }

    private void insertExcludeRule(
            long ruleId, long feedbackId, boolean active, String normalizedQuery, String filters, String version) {
        exec("INSERT INTO npick.search_rule (search_rule_id, query_fingerprint, normalized_query,"
                + " normalized_filters_json, normalization_version, action, target_scene_id, source_feedback_id,"
                + " active, created_at, updated_at) VALUES (" + ruleId + ", '" + SEARCH.fingerprint() + "', '"
                + normalizedQuery + "', '" + filters + "'::jsonb, '" + version + "', 'exclude_scene', 9301, "
                + feedbackId + ", " + active + ", now(), now())");
    }

    private void insertPatchRule(long ruleId, long feedbackId) {
        exec("INSERT INTO npick.search_rule (search_rule_id, query_fingerprint, normalized_query,"
                + " normalized_filters_json, normalization_version, action, target_scene_id, source_feedback_id,"
                + " active, created_at, updated_at, condition_json, patch_json) VALUES (" + ruleId + ", '"
                + SEARCH.fingerprint() + "', '" + SEARCH.normalizedQuery()
                + "', '{\"region\":[\"제주\"]}'::jsonb, 'v1', 'patch_parse', NULL, " + feedbackId
                + ", true, now(), now(), '{}'::jsonb, '{}'::jsonb)");
    }

    private void exec(String sql) {
        em.createNativeQuery(sql).executeUpdate();
    }
}
