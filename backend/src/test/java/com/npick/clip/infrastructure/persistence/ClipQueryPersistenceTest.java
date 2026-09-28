package com.npick.clip.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.npick.clip.application.query.ClipQueryPort;
import com.npick.clip.application.query.ClipQueryService;
import com.npick.clip.application.query.detail.GetClipUseCase;
import com.npick.clip.application.query.list.GetClipsUseCase;
import com.npick.clip.infrastructure.persistence.query.JpaClipQueryAdapter;
import com.npick.clip.presentation.controller.ClipQueryController;
import com.npick.common.error.handler.ErrorTypeHttpStatusMapper;
import com.npick.common.error.handler.GlobalExceptionHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real baseline DB fixtures, query service and HTTP serialization; no registration/worker execution. */
@DataJpaTest(
        properties = {
            "spring.autoconfigure.exclude=",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.default_schema=npick",
            "spring.jpa.properties.hibernate.generate_statistics=true"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Import({
    JpaClipQueryAdapter.class,
    ClipQueryService.class,
    com.npick.pipeline.application.query.ProcessingDetailsQueryService.class,
    com.npick.pipeline.infrastructure.persistence.query.JdbcProcessingDetailsQueryAdapter.class,
    com.npick.member.application.query.MemberSummaryQueryService.class,
    com.npick.member.infrastructure.persistence.query.JpaMemberSummaryQueryAdapter.class,
    ClipQueryPersistenceTest.JsonConfig.class
})
class ClipQueryPersistenceTest {
    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    GetClipsUseCase list;

    @Autowired
    GetClipUseCase detail;

    @Autowired
    ClipQueryPort port;

    @Autowired
    EntityManagerFactory emf;

    MockMvc mvc;
    private static final Instant CREATED = Instant.parse("2026-09-08T01:00:00Z");

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.npick.pipeline.application.port.WorkerArtifactPort artifacts;

    @org.springframework.boot.test.context.TestConfiguration
    static class JsonConfig {
        @org.springframework.context.annotation.Bean
        tools.jackson.databind.json.JsonMapper jsonMapper() {
            return tools.jackson.databind.json.JsonMapper.builder().build();
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        com.npick.support.NpickPostgres.datasource(properties);
    }

    @BeforeEach
    void setup() {
        jdbc.update(
                "INSERT INTO npick.member VALUES (1, 'query-reviewer', 'test-only', '검수자', 'reviewer', now(), now())");
        mvc = MockMvcBuilders.standaloneSetup(new ClipQueryController(
                        list,
                        detail,
                        org.mockito.Mockito.mock(
                                com.npick.clip.application.query.analysis.GetClipAnalysisScenesUseCase.class)))
                .setCustomArgumentResolvers(new com.npick.common.security.resolver.CurrentMemberArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler(new ErrorTypeHttpStatusMapper()))
                .build();
    }

    @Test
    void emptyListAndMissingClip() throws Exception {
        mvc.perform(get("/api/v1/clips"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.total_elements").value(0))
                .andExpect(jsonPath("$.data.total_pages").value(0))
                .andExpect(jsonPath("$.data.has_next").value(false));
        mvc.perform(get("/api/v1/clips/42"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLIP_QUERY_404"));
    }

    @Test
    void pagesUseStableCreationTimeAndIdOrderAndExcludeDeleted() throws Exception {
        clip(10, CREATED);
        clip(20, CREATED);
        clip(5, CREATED.plusSeconds(1));
        clip(99, CREATED.plusSeconds(2));
        jdbc.update("UPDATE npick.clip SET deleted_at=now() WHERE clip_id=99");
        mvc.perform(get("/api/v1/clips?page=0&size=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].clip_id").value("5"))
                .andExpect(jsonPath("$.data.items[1].clip_id").value("20"))
                .andExpect(jsonPath("$.data.total_elements").value(3))
                .andExpect(jsonPath("$.data.total_pages").value(2))
                .andExpect(jsonPath("$.data.has_next").value(true));
        mvc.perform(get("/api/v1/clips?page=1&size=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].clip_id").value("10"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.has_next").value(false));
        mvc.perform(get("/api/v1/clips?page=2147483647&size=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.total_elements").value(3));
        assertThat(port.findPage(Integer.MAX_VALUE, 100, java.util.List.of(), null))
                .isEmpty();
        mvc.perform(get("/api/v1/clips/99")).andExpect(status().isNotFound());
    }

    @Test
    void noRunIsUnknownRatherThanSuccessful() throws Exception {
        clip(9007199254740993L, CREATED);
        String body = mvc.perform(get("/api/v1/clips/9007199254740993"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clip.clip_id").value("9007199254740993"))
                .andExpect(jsonPath("$.data.clip.search_available").value(false))
                .andExpect(jsonPath("$.data.processing_details_availability").doesNotExist())
                .andExpect(jsonPath("$.data.default_transcript_source").value("none"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body)
                .contains("\"latest_run\":null", "\"processing_details\":null", "\"active_pipeline_run_id\":null")
                .doesNotContain("\"status\":\"succeeded\"", "\"ready\"", "retryable\":false");
    }

    @Test
    void creationTimeWinsOverProcessingNumberIdUpdateTimeAndActiveRun() throws Exception {
        clip(10, CREATED);
        run(999, 10, 5, "succeeded", null, "{}");
        run(
                100,
                10,
                2,
                "failed",
                "MODEL_TIMEOUT",
                "{\"asr\":{\"status\":\"failed\",\"raw_response\":\"/srv/private/model\"}}");
        jdbc.update(
                "UPDATE npick.pipeline_run SET updated_at=? WHERE pipeline_run_id=999",
                Timestamp.from(CREATED.plusSeconds(100)));
        jdbc.update(
                "UPDATE npick.pipeline_run SET created_at=? WHERE pipeline_run_id=100",
                Timestamp.from(CREATED.plusSeconds(1)));
        jdbc.update("UPDATE npick.clip SET active_pipeline_run_id=999 WHERE clip_id=10");
        mvc.perform(get("/api/v1/clips/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clip.latest_run.pipeline_run_id").value("100"))
                .andExpect(jsonPath("$.data.clip.latest_run.processing_no").value(2))
                .andExpect(jsonPath("$.data.clip.latest_run.status").value("failed"))
                .andExpect(jsonPath("$.data.clip.latest_run.error_code").value("MODEL_TIMEOUT"))
                .andExpect(jsonPath("$.data.clip.search_available").value(true))
                .andExpect(jsonPath("$.data.clip.active_pipeline_run_id").value("999"))
                .andExpect(jsonPath("$.data.processing_details_availability").doesNotExist());
        assertThat(list.getClips(0, 20).items().getFirst().latestRun())
                .isEqualTo(detail.getClip(10).latestRun());
    }

    @Test
    void sameCreationTimeUsesIdAsDeterministicTieBreakerWithinEachClip() throws Exception {
        clip(10, CREATED);
        clip(20, CREATED);
        run(100, 10, 9, "failed", null, "{}");
        run(200, 10, 1, "running", null, "{}");
        run(300, 20, 1, "queued", null, "{}");
        jdbc.update(
                "UPDATE npick.pipeline_run SET updated_at=?, finished_at=? WHERE pipeline_run_id=100",
                Timestamp.from(CREATED.plusSeconds(100)),
                Timestamp.from(CREATED.plusSeconds(100)));
        assertThat(detail.getClip(10).latestRun().pipelineRunId()).isEqualTo(200);
        assertThat(detail.getClip(20).latestRun().pipelineRunId()).isEqualTo(300);
        var items = list.getClips(0, 20).items();
        assertThat(items).hasSize(2);
        assertThat(items.get(1).latestRun()).isEqualTo(detail.getClip(10).latestRun());
        String body = mvc.perform(get("/api/v1/clips/10"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body)
                .contains("\"record_status\":\"unavailable\"")
                .doesNotContain("processing_details_availability", "contract_pending", "\"retryable\":true");
    }

    @Test
    void allStoredRunStatesAreReturnedWithoutInventingSearchReadiness() {
        var states = java.util.List.of("queued", "running", "succeeded", "failed");
        for (int i = 0; i < states.size(); i++) {
            clip(i + 10, CREATED);
            run(i + 100, i + 10, 1, states.get(i), null, "{}");
            assertThat(detail.getClip(i + 10).latestRun().status()).isEqualTo(states.get(i));
            assertThat(detail.getClip(i + 10).activePipelineRunId()).isNull();
        }
    }

    @Test
    void detailedLookingJsonIsNotAnApprovedProducerContract() throws Exception {
        clip(10, CREATED);
        // Deliberately fabricated input: neither a producer contract nor a successful mapping fixture.
        run(
                100,
                10,
                1,
                "queued",
                null,
                "{\"schema_version\":1,\"stages\":[{\"code\":\"asr\",\"status\":\"succeeded\"}],"
                        + "\"manual_retry\":{\"allowed\":true},\"missing_channels\":[],"
                        + "\"transcript\":{\"used_sources\":[\"asr\"]}}");
        for (String storedStatus : java.util.List.of("queued", "running", "succeeded", "failed")) {
            jdbc.update("UPDATE npick.pipeline_run SET status=? WHERE pipeline_run_id=100", storedStatus);
            String body = mvc.perform(get("/api/v1/clips/10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.clip.latest_run.status").value(storedStatus))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat(body)
                    .contains("\"record_status\":\"unavailable\"")
                    .doesNotContain("schema_version", "manual_retry", "used_sources", "contract_pending");
        }
    }

    @Test
    void storedRunTimestampsArePreservedAndDetailUsesOneProjectionPlusOneRegistrantLookup() throws Exception {
        clip(10, CREATED);
        run(100, 10, 1, "failed", "MODEL_TIMEOUT", "{}");
        Instant started = CREATED.plusSeconds(2);
        Instant finished = CREATED.plusSeconds(12);
        jdbc.update(
                "UPDATE npick.pipeline_run SET started_at=?, finished_at=? WHERE pipeline_run_id=100",
                Timestamp.from(started),
                Timestamp.from(finished));
        var statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        mvc.perform(get("/api/v1/clips/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clip.latest_run.created_at").value(CREATED.toString()))
                .andExpect(jsonPath("$.data.clip.latest_run.started_at").value(started.toString()))
                .andExpect(jsonPath("$.data.clip.latest_run.finished_at").value(finished.toString()))
                .andExpect(jsonPath("$.data.clip.latest_run.error_code").value("MODEL_TIMEOUT"));
        // 투영 1 + 등록자 조회 1. 등록자는 로그인 ID 만 읽으며 엔티티를 적재하지 않는다.
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        assertThat(statistics.getEntityLoadCount()).isZero();
    }

    @Test
    void privateStorageAndUncontractedJsonNeverLeaveApi() throws Exception {
        clip(10, CREATED);
        jdbc.update(
                "UPDATE npick.clip SET transcript_file_key='/srv/private/subtitle', script_text='private-script', transcript_source='provided' WHERE clip_id=10");
        run(
                100,
                10,
                1,
                "failed",
                "/srv/private/error",
                "{\"asr\":{\"status\":\"succeeded\",\"raw_response\":\"secret-model-output\"},\"retryable\":true}");
        for (String path : java.util.List.of("/api/v1/clips", "/api/v1/clips/10")) {
            String body = mvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat(body)
                    .doesNotContain(
                            "/srv",
                            "storage_key",
                            "content_hash",
                            "transcript_file_key",
                            "stage_states_json",
                            "raw_response",
                            "secret-model-output",
                            "private-script",
                            "registered_by_id",
                            "retryable\":true")
                    .contains("\"error_code\":null");
        }
        mvc.perform(get("/api/v1/clips/10"))
                .andExpect(jsonPath("$.data.has_subtitle").value(true))
                .andExpect(jsonPath("$.data.has_script").value(true))
                .andExpect(jsonPath("$.data.default_transcript_source").value("none"));
    }

    @Test
    void listUsesThreeJpaStatementsAndOneBatchProgressLookupWithoutArtifacts() {
        for (int i = 1; i <= 25; i++) {
            clip(i, CREATED);
            run(i + 100, i, 1, "queued", null, "{}");
        }
        var statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        // 투영 + 집계 + 등록자. 셋 다 배치라 페이지 크기가 늘어도 질의 수는 고정이다.
        assertThat(list.getClips(0, 20).items()).hasSize(20);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
        org.mockito.Mockito.verifyNoInteractions(artifacts);
        assertThat(statistics.getEntityLoadCount()).isZero();
        statistics.clear();
        assertThat(list.getClips(0, 1).items()).hasSize(1);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    @Test
    void latestStatusFiltersAndGlobalCountsIgnoreOldRunsAndDeletedClips() throws Exception {
        for (int i = 1; i <= 7; i++) clip(i, CREATED);
        run(101, 1, 1, "queued", null, "{}");
        run(102, 2, 1, "running", null, "{}");
        run(103, 3, 1, "succeeded", null, "{}");
        run(203, 3, 2, "failed", "STAGE_FAILED", "{}");
        jdbc.update("UPDATE npick.clip SET active_pipeline_run_id=103 WHERE clip_id=3");
        run(104, 4, 1, "succeeded", null, "{}");
        run(106, 6, 1, "queued", null, "{}");
        run(107, 7, 1, "failed", "STAGE_FAILED", "{}");
        jdbc.update("UPDATE npick.clip SET deleted_at=now() WHERE clip_id=7");
        mvc.perform(get("/api/v1/clips?status=queued,running&size=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].clip_id").value("6"))
                .andExpect(jsonPath("$.data.total_elements").value(3))
                .andExpect(jsonPath("$.data.total_pages").value(3))
                .andExpect(jsonPath("$.data.has_next").value(true))
                .andExpect(jsonPath("$.data.run_counts.queued").value(2))
                .andExpect(jsonPath("$.data.run_counts.running").value(1))
                .andExpect(jsonPath("$.data.run_counts.failed").value(1))
                .andExpect(jsonPath("$.data.run_counts.succeeded").value(1))
                .andExpect(jsonPath("$.data.run_counts.no_run").value(1));
        mvc.perform(get("/api/v1/clips?status=queued&status=running&page=2&size=1"))
                .andExpect(jsonPath("$.data.items[0].clip_id").value("1"))
                .andExpect(jsonPath("$.data.has_next").value(false));
        mvc.perform(get("/api/v1/clips?status=failed"))
                .andExpect(jsonPath("$.data.items[0].clip_id").value("3"))
                .andExpect(jsonPath("$.data.items[0].search_available").value(true))
                .andExpect(jsonPath("$.data.items[0].latest_run.status").value("failed"));
        mvc.perform(get("/api/v1/clips?status=no_run"))
                .andExpect(jsonPath("$.data.items[0].clip_id").value("5"))
                .andExpect(jsonPath("$.data.items[0].progress").doesNotExist());
        mvc.perform(get("/api/v1/clips?status=failed&page=10"))
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.total_elements").value(1))
                .andExpect(jsonPath("$.data.run_counts.queued").value(2));
        mvc.perform(get("/api/v1/clips?status=ready"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CLIP_QUERY_400_001"));
    }

    @Test
    void rejectsInvalidPagesAndIds() throws Exception {
        for (String query : java.util.List.of(
                "page=-1",
                "size=0",
                "size=-1",
                "size=101",
                "page=abc",
                "size=2147483648",
                "page=2147483647&size=100")) {
            mvc.perform(get("/api/v1/clips?" + query)).andExpect(status().isBadRequest());
        }
        for (String id : java.util.List.of("0", "-1", "abc", "9223372036854775808")) {
            mvc.perform(get("/api/v1/clips/" + id)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void listShowsTheLoginIdOfEachRegistrant() throws Exception {
        member(2, "arch01");
        clip(10, CREATED, 1);
        clip(20, CREATED.plusSeconds(1), 2);

        mvc.perform(get("/api/v1/clips"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].registered_by.login_id").value("arch01"))
                .andExpect(jsonPath("$.data.items[1].registered_by.login_id").value("query-reviewer"));
    }

    @Test
    void ownerFilterReturnsOnlyClipsRegisteredByThatMember() {
        member(2, "other-reviewer");
        clip(10, CREATED, 1);
        clip(20, CREATED.plusSeconds(1), 2);

        var mine = list.getClips(0, 20, java.util.List.of(), 1L);

        assertThat(mine.items().stream().map(item -> item.clipId()).toList()).containsExactly(10L);
        assertThat(mine.totalElements()).isEqualTo(1);
    }

    @Test
    void ownerFilterAlsoNarrowsRunCounts() {
        member(2, "other-reviewer");
        clip(10, CREATED, 1);
        run(101, 10, 1, "queued", null, "{}");
        clip(20, CREATED.plusSeconds(1), 2);
        run(102, 20, 1, "failed", null, "{}");

        var mine = list.getClips(0, 20, java.util.List.of(), 1L);

        assertThat(mine.runCounts()).containsEntry("queued", 1L).containsEntry("failed", 0L);
    }

    @Test
    void withoutOwnerFilterEveryReviewersClipIsListed() {
        member(2, "other-reviewer");
        clip(10, CREATED, 1);
        clip(20, CREATED.plusSeconds(1), 2);

        var all = list.getClips(0, 20, java.util.List.of(), null);

        assertThat(all.items().stream().map(item -> item.clipId()).toList()).containsExactly(20L, 10L);
        assertThat(all.runCounts()).containsEntry("no_run", 2L);
    }

    private void member(long id, String loginId) {
        jdbc.update(
                "INSERT INTO npick.member VALUES (?, ?, 'test-only', '검수자', 'reviewer', now(), now())", id, loginId);
    }

    private void clip(long id, Instant created, long registeredById) {
        jdbc.update(
                "INSERT INTO npick.clip (clip_id,source_type,storage_key,content_hash,title,transcript_source,registered_by_id,created_at,updated_at) VALUES (?, 'archive', '/srv/private/original', ?, '테스트 영상', 'none', ?, ?, ?)",
                id,
                Long.toString(id),
                registeredById,
                Timestamp.from(created),
                Timestamp.from(created));
    }

    private void clip(long id, Instant created) {
        jdbc.update(
                "INSERT INTO npick.clip (clip_id,source_type,storage_key,content_hash,title,transcript_source,registered_by_id,created_at,updated_at) VALUES (?, 'archive', '/srv/private/original', ?, '테스트 영상', 'none', 1, ?, ?)",
                id,
                Long.toString(id),
                Timestamp.from(created),
                Timestamp.from(created));
    }

    private void run(long id, long clipId, int number, String status, String error, String json) {
        jdbc.update(
                "INSERT INTO npick.pipeline_run (pipeline_run_id,clip_id,processing_no,pipeline_version,status,stage_states_json,error_code,created_at,updated_at) VALUES (?, ?, ?, 'test-v1', ?, cast(? as jsonb), ?, ?, ?)",
                id,
                clipId,
                number,
                status,
                json,
                error,
                Timestamp.from(CREATED),
                Timestamp.from(CREATED));
    }
}
