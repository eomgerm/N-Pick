package com.npick.clip.presentation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.npick.clip.application.query.analysis.ClipAnalysisScenesQueryService;
import com.npick.clip.application.query.analysis.GetClipAnalysisScenesUseCase;
import com.npick.clip.application.query.detail.GetClipUseCase;
import com.npick.clip.application.query.list.GetClipsUseCase;
import com.npick.clip.infrastructure.persistence.query.JdbcClipAnalysisScenesQueryAdapter;
import com.npick.clip.presentation.controller.ClipQueryController;
import com.npick.common.error.handler.ErrorTypeHttpStatusMapper;
import com.npick.common.error.handler.GlobalExceptionHandler;
import com.npick.tag.application.query.ResolveSceneTagsUseCase;
import com.npick.tag.domain.model.EffectiveTag;
import com.npick.tag.domain.model.TagType;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DataJpaTest(
        properties = {
            "spring.autoconfigure.exclude=",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.default_schema=npick"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JdbcClipAnalysisScenesQueryAdapter.class, ClipAnalysisScenesQueryService.class})
class ClipAnalysisScenesHttpIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager entityManager;

    @Autowired
    GetClipAnalysisScenesUseCase analysis;

    @MockitoBean
    ResolveSceneTagsUseCase tags;

    MockMvc mvc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        com.npick.support.NpickPostgres.datasource(properties);
    }

    @BeforeEach
    void setup() {
        jdbc.update(
                "INSERT INTO npick.member VALUES (7601, 'analysis-reviewer', 'test-only', '검수자', 'reviewer', now(), now())");
        mvc = MockMvcBuilders.standaloneSetup(new ClipQueryController(
                        org.mockito.Mockito.mock(GetClipsUseCase.class),
                        org.mockito.Mockito.mock(GetClipUseCase.class),
                        analysis))
                .setControllerAdvice(new GlobalExceptionHandler(new ErrorTypeHttpStatusMapper()))
                .build();
    }

    @Test
    void returnsRunScopedAnalysisResultsWithoutInventingMissingFields() throws Exception {
        clip(7610);
        run(7620, 7610);
        jdbc.update("UPDATE npick.clip SET active_pipeline_run_id=7620 WHERE clip_id=7610");
        scene(7632, 7610, 7620, 4_000, 8_000, null, null, null);
        scene(7631, 7610, 7620, 0, 4_000, "서울역 앞 도로", "현재 교통 상황입니다.", "provided");
        jdbc.update("INSERT INTO npick.keyframe VALUES (7652, 7631, 2500, 'private/later.jpg')");
        jdbc.update("INSERT INTO npick.keyframe VALUES (7651, 7631, 1800, 'private/representative.jpg')");
        jdbc.update("INSERT INTO npick.ocr_observation VALUES (7661, 7651, '서울역', '서울역', 0.9, '{}')");
        jdbc.update("INSERT INTO npick.ocr_observation VALUES (7662, 7652, '서울역', '서울역', 0.8, '{}')");
        jdbc.update("INSERT INTO npick.ocr_observation VALUES (7663, 7652, '출구', '출구', 0.7, '{}')");
        when(tags.resolveForRun(7610, 7620, List.of(7631L, 7632L)))
                .thenReturn(Map.of(
                        7631L,
                        List.of(new EffectiveTag(
                                7631,
                                7610,
                                7671,
                                TagType.LOCATION,
                                "서울역",
                                "서울역",
                                EffectiveTag.Verification.UNVERIFIED,
                                EffectiveTag.Scope.SCENE,
                                "vlm"))));

        mvc.perform(get("/api/v1/clips/7610/runs/7620/scenes?page=0&size=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clip_id").value("7610"))
                .andExpect(jsonPath("$.data.pipeline_run_id").value("7620"))
                .andExpect(jsonPath("$.data.search_applied").value(true))
                .andExpect(jsonPath("$.data.summary.total_scenes").value(2))
                .andExpect(jsonPath("$.data.summary.captioned_scenes").value(1))
                .andExpect(jsonPath("$.data.summary.transcript_scenes").value(1))
                .andExpect(jsonPath("$.data.summary.tagged_scenes").value(1))
                .andExpect(jsonPath("$.data.items[0].scene_id").value("7631"))
                .andExpect(jsonPath("$.data.items[0].scene_index").value(1))
                .andExpect(jsonPath("$.data.items[0].representative_frame_timestamp_ms")
                        .value(1800))
                .andExpect(jsonPath("$.data.items[0].caption").value("서울역 앞 도로"))
                .andExpect(jsonPath("$.data.items[0].transcript.text").value("현재 교통 상황입니다."))
                .andExpect(jsonPath("$.data.items[0].transcript.source").value("provided"))
                .andExpect(jsonPath("$.data.items[0].tags[0].tag_id").value("7671"))
                .andExpect(jsonPath("$.data.items[0].tags[0].type").value("location"))
                .andExpect(jsonPath("$.data.items[0].tags[0].scope").value("scene"))
                .andExpect(jsonPath("$.data.items[0].tags[0].verification").value("unverified"))
                .andExpect(jsonPath("$.data.items[0].ocr_texts[0]").value("서울역"))
                .andExpect(jsonPath("$.data.items[0].ocr_texts[1]").value("출구"))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.total_elements").value(2))
                .andExpect(jsonPath("$.data.total_pages").value(2))
                .andExpect(jsonPath("$.data.has_next").value(true));

        mvc.perform(get("/api/v1/clips/7610/runs/7620/scenes?page=1&size=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].scene_id").value("7632"))
                .andExpect(jsonPath("$.data.items[0].scene_index").value(2))
                .andExpect(jsonPath("$.data.items[0].caption").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.items[0].transcript").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.items[0].representative_frame_timestamp_ms")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.items[0].tags").isEmpty())
                .andExpect(jsonPath("$.data.items[0].ocr_texts").isEmpty());
    }

    @Test
    void reportsWhetherTheRequestedRunIsAppliedToSearch() throws Exception {
        clip(7710);
        run(7720, 7710);
        run(7721, 7710);
        jdbc.update("UPDATE npick.clip SET active_pipeline_run_id=7720 WHERE clip_id=7710");
        scene(7731, 7710, 7721, 0, 1_000, "새 처리", null, null);
        when(tags.resolveForRun(7710, 7721, List.of(7731L))).thenReturn(Map.of());

        mvc.perform(get("/api/v1/clips/7710/runs/7721/scenes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.search_applied").value(false))
                .andExpect(jsonPath("$.data.items[0].caption").value("새 처리"));
    }

    @Test
    void rejectsForeignRunDeletedClipAndInvalidPage() throws Exception {
        clip(7810);
        clip(7811);
        run(7820, 7810);
        run(7821, 7811);

        mvc.perform(get("/api/v1/clips/7810/runs/7821/scenes"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLIP_QUERY_404"));
        jdbc.update("UPDATE npick.clip SET deleted_at=now() WHERE clip_id=7810");
        mvc.perform(get("/api/v1/clips/7810/runs/7820/scenes"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLIP_QUERY_404"));
        for (String query : List.of("page=-1", "size=0", "size=101", "page=2147483647&size=100")) {
            mvc.perform(get("/api/v1/clips/7811/runs/7821/scenes?" + query))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("CLIP_QUERY_400"));
        }
    }

    private void clip(long clipId) {
        jdbc.update(
                "INSERT INTO npick.clip (clip_id, source_type, storage_key, content_hash, title, transcript_source, registered_by_id, created_at, updated_at) VALUES (?, 'archive', ?, ?, '분석 영상', 'none', 7601, ?, ?)",
                clipId,
                "private/" + clipId,
                "hash-" + clipId,
                Timestamp.from(Instant.parse("2026-09-26T01:00:00Z")),
                Timestamp.from(Instant.parse("2026-09-26T01:00:00Z")));
    }

    private void run(long runId, long clipId) {
        jdbc.update(
                "INSERT INTO npick.pipeline_run (pipeline_run_id, clip_id, processing_no, pipeline_version, status, stage_states_json, created_at, updated_at) VALUES (?, ?, (SELECT count(*) + 1 FROM npick.pipeline_run WHERE clip_id=?), 'test', 'succeeded', '{}', now(), now())",
                runId,
                clipId,
                clipId);
    }

    private void scene(
            long sceneId,
            long clipId,
            long runId,
            long start,
            long end,
            String caption,
            String transcript,
            String transcriptSource) {
        jdbc.update(
                "INSERT INTO npick.scene (scene_id, clip_id, pipeline_run_id, start_time_ms, end_time_ms, caption, shot_type, transcript_text, transcript_source, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 'wide', ?, ?, now(), now())",
                sceneId,
                clipId,
                runId,
                start,
                end,
                caption,
                transcript,
                transcriptSource);
    }
}
