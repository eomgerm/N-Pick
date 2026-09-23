package com.npick.search;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.npick.common.security.AuthenticatedMember;
import com.npick.search.application.error.SearchExecutionErrorCode;
import com.npick.search.application.port.CompleteSearchExecution;
import com.npick.search.application.port.RecordSearchExecutionResolution;
import com.npick.search.application.port.SearchExecutionRecordPort;
import com.npick.search.application.port.SearchRecordingException;
import com.npick.search.application.port.StartSearchExecution;
import com.npick.search.application.query.dense.DenseSearchSettings;
import com.npick.search.application.query.execution.SearchExecutionDetailQueryPort;
import com.npick.search.application.query.fusion.SearchConfigSnapshot;
import com.npick.search.application.resolution.SearchDegradedReason;
import com.npick.search.domain.model.ExplicitDateFilters;
import com.npick.search.domain.model.FusionChannel;
import com.npick.search.domain.model.FusionSettings;
import com.npick.search.domain.model.LexicalSearchSettings;
import com.npick.search.domain.model.NormalizedSearch;
import com.npick.search.domain.model.ParseRule;
import com.npick.search.domain.model.ParseRuleOutcome;
import com.npick.search.domain.model.QueryResolution;
import com.npick.search.domain.model.SoftRankingSettings;
import com.npick.search.domain.model.SoftSignal;
import com.npick.search.domain.model.StructuredAxis;
import com.npick.search.domain.model.StructuredScoreSettings;
import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SearchExecutionRecordingDbTest {
    private static final long MEMBER_ID = 1606001L;
    private static final long OTHER_MEMBER_ID = 1606002L;
    private static final long CLIP_ID = 1606010L;
    private static final long RUN_ID = 1606020L;
    private static final long SCENE_ID = 1606030L;

    @Autowired
    private SearchExecutionRecordPort records;

    @Autowired
    private SearchExecutionDetailQueryPort details;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        NpickPostgres.datasource(properties);
    }

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.update(
                "INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at) VALUES (?, 's60-owner', 'h', 'owner', 'editor', now(), now())",
                MEMBER_ID);
        jdbc.update(
                "INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at) VALUES (?, 's60-other', 'h', 'other', 'editor', now(), now())",
                OTHER_MEMBER_ID);
        jdbc.update(
                "INSERT INTO npick.clip (clip_id, source_type, storage_key, content_hash, transcript_source, registered_by_id, created_at, updated_at) VALUES (?, 'broadcast', 's60/clip', repeat('a', 64), 'none', ?, now(), now())",
                CLIP_ID,
                MEMBER_ID);
        jdbc.update(
                "INSERT INTO npick.pipeline_run (pipeline_run_id, clip_id, processing_no, pipeline_version, status, stage_states_json, created_at, updated_at) VALUES (?, ?, 1, 'v1', 'succeeded', '{}'::jsonb, now(), now())",
                RUN_ID,
                CLIP_ID);
        jdbc.update(
                "INSERT INTO npick.scene (scene_id, clip_id, pipeline_run_id, start_time_ms, end_time_ms, shot_type, created_at, updated_at) VALUES (?, ?, ?, 0, 1000, 'b_roll', now(), now())",
                SCENE_ID,
                CLIP_ID,
                RUN_ID);
    }

    @AfterEach
    void cleanup() {
        jdbc.update(
                "DELETE FROM npick.search_result WHERE search_execution_id IN (SELECT search_execution_id FROM npick.search_execution WHERE searched_by_id IN (?, ?))",
                MEMBER_ID,
                OTHER_MEMBER_ID);
        jdbc.update("DELETE FROM npick.search_execution WHERE searched_by_id IN (?, ?)", MEMBER_ID, OTHER_MEMBER_ID);
        jdbc.update("DELETE FROM npick.scene WHERE scene_id=?", SCENE_ID);
        jdbc.update("DELETE FROM npick.pipeline_run WHERE pipeline_run_id=?", RUN_ID);
        jdbc.update("DELETE FROM npick.clip WHERE clip_id=?", CLIP_ID);
        jdbc.update("DELETE FROM npick.member WHERE member_id IN (?, ?)", MEMBER_ID, OTHER_MEMBER_ID);
    }

    @Test
    @DisplayName("시작과 완료를 독립 저장하고 소유자와 검수자만 원본·최종 해석 및 결과 snapshot을 조회한다")
    void recordsAndReadsSnapshot() throws Exception {
        QueryResolution raw = resolution(QueryResolution.Intent.UNKNOWN);
        QueryResolution verified = resolution(QueryResolution.Intent.SCENE_SEARCH);
        long executionId = records.start(
                new StartSearchExecution(
                        MEMBER_ID, StartSearchExecution.ExecutionType.NORMAL, null, "명절 교통", null));

        assertThat(jdbc.queryForObject(
                        "SELECT normalized_query FROM npick.search_execution WHERE search_execution_id=?",
                        String.class,
                        executionId))
                .isNull();
        assertThat(jdbc.queryForObject(
                        "SELECT resolver_output_json FROM npick.search_execution WHERE search_execution_id=?",
                        String.class,
                        executionId))
                .isNull();

        records.recordResolution(new RecordSearchExecutionResolution(
                executionId,
                ExplicitDateFilters.none(),
                NormalizedSearch.of("명절 교통", Map.of(), "normalizer/v1"),
                new RecordSearchExecutionResolution.ResolverOutput(
                        raw, verified, "query-resolver/v2", "prompt/v3", "model/v1"),
                List.of(),
                StartSearchExecution.ParseSource.RESOLVER,
                12,
                List.of()));

        assertThat(jdbc.queryForObject(
                        "SELECT status FROM npick.search_execution WHERE search_execution_id=?",
                        String.class,
                        executionId))
                .isEqualTo("running");

        ParseRule rule = ParseRule.unparsed(77L, "{\"condition\":{}}", "old schema");
        records.complete(new CompleteSearchExecution(
                executionId,
                CompleteSearchExecution.ExecutionStatus.DEGRADED,
                List.of(SearchDegradedReason.DENSE_UNAVAILABLE),
                null,
                verified,
                List.of(ParseRuleOutcome.incompatible(rule, "old schema")),
                new CompleteSearchExecution.CandidateRecord(
                        List.of(Map.of("scene_id", Long.toString(SCENE_ID))), null, Map.of()),
                new CompleteSearchExecution.FilterRecord(
                        1,
                        List.of("candidate_pool_exhausted"),
                        new CompleteSearchExecution.GuardRecord(false, List.of())),
                List.of(),
                List.of(new CompleteSearchExecution.RankedScene(
                        SCENE_ID, 1, Map.of("display", Map.of("display_name", "당시 제목"), "score", 0.91))),
                config(),
                34,
                null));

        var detail = details.findVisible(executionId, MEMBER_ID, false).orElseThrow();
        assertThat(detail.status()).isEqualTo("degraded");
        assertThat(detail.resolverOutput().at("/raw/intent").asString()).isEqualTo("unknown");
        assertThat(detail.parsedQuery().path("intent").asString()).isEqualTo("scene_search");
        assertThat(detail.appliedRules().get(0).path("status").asString()).isEqualTo("skipped_incompatible");
        assertThat(detail.filtered().path("returned_count").asInt()).isEqualTo(1);
        assertThat(detail.searchConfig().at("/fusion/rrf_k").asDouble()).isEqualTo(60.0);
        assertThat(detail.searchConfig().at("/fusion/rrfK").isMissingNode()).isTrue();
        assertThat(detail.verificationContext()).isNull();
        assertThat(detail.results()).singleElement().satisfies(result -> {
            assertThat(result.sceneId()).isEqualTo(SCENE_ID);
            assertThat(result.explain().at("/display/display_name").asString()).isEqualTo("당시 제목");
        });
        assertThat(details.findVisible(executionId, OTHER_MEMBER_ID, false)).isEmpty();
        assertThat(details.findVisible(executionId, OTHER_MEMBER_ID, true)).isPresent();
        mockMvc.perform(get("/api/v1/search/executions/{id}", executionId)
                        .with(user(new AuthenticatedMember(MEMBER_ID, "s60-owner", "h", "EDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.search_execution_id").value(Long.toString(executionId)))
                .andExpect(jsonPath("$.data.results[0].search_result_id").isString())
                .andExpect(jsonPath("$.data.resolver_output.raw.intent").value("unknown"))
                .andExpect(jsonPath("$.data.search_config.fusion.rrf_k").value(60.0))
                .andExpect(jsonPath("$.data.search_config.fusion.rrfK").doesNotExist());
        mockMvc.perform(get("/api/v1/search/executions/{id}", executionId)
                        .with(user(new AuthenticatedMember(OTHER_MEMBER_ID, "s60-other", "h", "EDITOR"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SRCH_404_001"));
        mockMvc.perform(get("/api/v1/search/executions/{id}", executionId)
                        .with(user(new AuthenticatedMember(OTHER_MEMBER_ID, "s60-reviewer", "h", "REVIEWER"))))
                .andExpect(status().isOk());

        assertThatThrownBy(() -> records.complete(new CompleteSearchExecution(
                        executionId,
                        CompleteSearchExecution.ExecutionStatus.SUCCEEDED,
                        List.of(),
                        null,
                        raw,
                        List.of(),
                        new CompleteSearchExecution.CandidateRecord(List.of(), null, Map.of()),
                        new CompleteSearchExecution.FilterRecord(
                                0, List.of(), new CompleteSearchExecution.GuardRecord(false, List.of())),
                        List.of(),
                        List.of(),
                        config(),
                        1,
                        null)))
                .isInstanceOf(SearchRecordingException.class);
        assertThat(details.findVisible(executionId, MEMBER_ID, false)
                        .orElseThrow()
                        .results())
                .singleElement()
                .satisfies(result -> assertThat(
                                result.explain().at("/display/display_name").asString())
                        .isEqualTo("당시 제목"));
    }

    @Test
    @DisplayName("순위 계산 전에 끊긴 실행을 failed로 닫고 기록 실패는 호출부로 던지지 않는다")
    void closesFailedExecutionWithoutThrowing() {
        long executionId = records.start(
                new StartSearchExecution(
                        MEMBER_ID, StartSearchExecution.ExecutionType.NORMAL, null, "명절 교통", null));

        // 해석이 오기 전이라 정규화 질의도 설정 snapshot도 없다. 이 상태로는 결과를 낸 실행이 될 수 없다는 것을
        // 스키마가 직접 막는다 (ck_execution_completed_snapshot). 코드 검증만으로는 다른 경로가 생기면 뚫린다.
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE npick.search_execution SET status='succeeded' WHERE search_execution_id=?",
                        executionId))
                .isInstanceOf(DataAccessException.class);

        records.fail(executionId, SearchExecutionErrorCode.ACTIVE_RULE_LOOKUP_FAILED.code(), 21);

        var detail = details.findVisible(executionId, MEMBER_ID, false).orElseThrow();
        assertThat(detail.status()).isEqualTo("failed");
        assertThat(detail.errorCode()).isEqualTo("SRCH_503_011");
        assertThat(detail.executionMs()).isEqualTo(21);
        assertThat(detail.results()).isEmpty();

        // 이미 닫힌 실행, 없는 실행, 잘못된 입력 어느 것도 예외가 되지 않는다. 기록 실패가 사용자에게 갈
        // 검색 실패 사유를 가리면 안 된다 (FRD §6.2).
        records.fail(executionId, SearchExecutionErrorCode.LEXICAL_SEARCH_FAILED.code(), 5);
        records.fail(Long.MAX_VALUE, SearchExecutionErrorCode.EXECUTION_NOT_RECORDED.code(), 5);
        records.fail(executionId, " ", 5);
        records.fail(executionId, SearchExecutionErrorCode.LEXICAL_SEARCH_FAILED.code(), -1);

        var unchanged = details.findVisible(executionId, MEMBER_ID, false).orElseThrow();
        assertThat(unchanged.errorCode()).isEqualTo("SRCH_503_011");
        assertThat(unchanged.executionMs()).isEqualTo(21);
    }

    private static QueryResolution resolution(QueryResolution.Intent intent) {
        return new QueryResolution(
                "query-resolver/v2", intent, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0.9);
    }

    private static SearchConfigSnapshot config() {
        Map<FusionChannel, Double> channels = Arrays.stream(FusionChannel.values())
                .collect(Collectors.toMap(
                        value -> value, value -> 1.0, (left, right) -> left, () -> new EnumMap<>(FusionChannel.class)));
        Map<StructuredAxis, Double> axes = Arrays.stream(StructuredAxis.values())
                .collect(Collectors.toMap(
                        value -> value,
                        value -> 1.0,
                        (left, right) -> left,
                        () -> new EnumMap<>(StructuredAxis.class)));
        Map<SoftSignal, Double> signals = Arrays.stream(SoftSignal.values())
                .collect(Collectors.toMap(
                        value -> value, value -> 0.0, (left, right) -> left, () -> new EnumMap<>(SoftSignal.class)));
        return new SearchConfigSnapshot(
                new FusionSettings(60, 0.1, channels, FusionSettings.WeightStatus.EXPERIMENTAL),
                new LexicalSearchSettings("lexical/v1", 1, 1, 1, 0.3, 100),
                new DenseSearchSettings("model@0123456789012345678901234567890123456789", 100, 2.0).snapshot(),
                new StructuredScoreSettings(StructuredScoreSettings.WeightStatus.EXPERIMENTAL, axes),
                new SoftRankingSettings(signals, 0.01, FusionSettings.WeightStatus.EXPERIMENTAL));
    }
}
