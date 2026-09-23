package com.npick.search;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.npick.common.security.AuthenticatedMember;
import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 컨트롤러→서비스→조회→실DB 전 구간. 「내 검색 기록」 목록·상세(S15P21A501-198).
@SpringBootTest
@AutoConfigureMockMvc
class SearchHistoryHttpDbTest {

    private static final AuthenticatedMember OWNER = new AuthenticatedMember(9001L, "editor01", "h", "EDITOR");
    private static final AuthenticatedMember OTHER = new AuthenticatedMember(9003L, "editor03", "h", "EDITOR");

    private static final String DISPLAY =
            "\"display\": {\"display_name\": \"예시 뉴스 · 서울역\", \"scene_description\": \"대합실 인파\","
                    + " \"start_time_ms\": 42000, \"end_time_ms\": 49000, \"shot_type\": \"b_roll\","
                    + " \"scene_type\": \"역사 인파\","
                    + " \"broadcast_date\": {\"value\": \"2026-09-14\", \"verification_status\": \"verified\"},"
                    + " \"filmed_date\": {\"value\": null, \"verification_status\": \"unknown\"}}";

    private static final String MATCH =
            "\"match\": {\"matched_keywords\": [{\"keyword\": \"서울역\", \"origin\": \"user\"}],"
                    + " \"match_evidence\": [{\"field\": \"ocr\","
                    + " \"value\": \"서울역\", \"source\": \"keyframe_ocr\","
                    + " \"verification_status\": \"verified\"}]}";

    private static final String FILTERED =
            "{\"returned_count\": 1, \"shortage_reasons\": [\"candidate_pool_exhausted\"],"
                    + " \"guard\": {\"incident_guard_active\": false, \"verdicts\": ["
                    + "{\"scene_id\": 9302, \"exclusion_reason\": \"explicit_date_conflict\"},"
                    + "{\"scene_id\": 9303, \"exclusion_reason\": null}]}}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        NpickPostgres.datasource(properties);
    }

    @Test
    @Transactional
    @DisplayName("목록은 계약 JSON 전 필드를 snake_case·문자열 ID 로 낸다")
    void listSerializesContractFields() throws Exception {
        seed();

        mockMvc.perform(get("/api/v1/search/history?page=0&size=10").with(user(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.total_elements").value(3))
                .andExpect(jsonPath("$.data.total_pages").value(1))
                .andExpect(jsonPath("$.data.has_next").value(false))
                .andExpect(jsonPath("$.data.items[0].search_execution_id").value("9702"))
                .andExpect(jsonPath("$.data.items[1].search_execution_id").value("9701"))
                .andExpect(jsonPath("$.data.items[1].query_text").value("서울역 귀성 인파"))
                .andExpect(jsonPath("$.data.items[1].explicit_filters.broadcast_date.from")
                        .value("2026-09-01"))
                .andExpect(jsonPath("$.data.items[1].created_at").value("2026-09-15T03:00:00Z"))
                .andExpect(jsonPath("$.data.items[1].status").value("succeeded"))
                .andExpect(jsonPath("$.data.items[1].snapshot_status").value("available"))
                .andExpect(jsonPath("$.data.items[1].result_count").value(1))
                .andExpect(jsonPath("$.data.items[1].representative_result.search_result_id")
                        .value("9801"))
                .andExpect(jsonPath("$.data.items[1].representative_result.scene_id")
                        .value("9301"))
                .andExpect(jsonPath("$.data.items[1].representative_result.clip_id")
                        .value("9101"))
                .andExpect(jsonPath("$.data.items[1].representative_result.display_name")
                        .value("예시 뉴스 · 서울역"))
                .andExpect(
                        jsonPath("$.data.items[1].representative_result.rank").value(1))
                // 목록 항목에는 상세 전용 필드를 싣지 않는다.
                .andExpect(jsonPath("$.data.items[1].search_snapshot").doesNotExist());
    }

    @Test
    @Transactional
    @DisplayName("더보기 이어보기 실행(parent_execution_id 있음)은 기록 목록에서 root 아래로 숨는다 (S15P21A501-280)")
    void listHidesLoadMoreContinuationExecutions() throws Exception {
        seed();
        // 9701 을 root 로 하는 더보기 이어보기 실행. 한 검색이라 기록엔 root(9701)만 한 줄로 보여야 한다.
        execution(9799, 9001, "서울역 귀성 인파", "succeeded", "original", null, "2026-09-15T03:05:00Z", null, "[]");
        exec("UPDATE npick.search_execution SET parent_execution_id = 9701 WHERE search_execution_id = 9799");

        mockMvc.perform(get("/api/v1/search/history?page=0&size=10").with(user(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_elements").value(3))
                .andExpect(jsonPath(
                        "$.data.items[*].search_execution_id", Matchers.not(Matchers.hasItem("9799"))));
    }

    @Test
    @Transactional
    @DisplayName("결과 저장이 불완전한 기록은 unavailable 이고 세 필드가 null 이지만 원문·시각은 유지한다")
    void listMarksIncompleteRecordUnavailable() throws Exception {
        seed();

        mockMvc.perform(get("/api/v1/search/history?page=0&size=10").with(user(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].search_execution_id").value("9702"))
                .andExpect(jsonPath("$.data.items[0].snapshot_status").value("unavailable"))
                .andExpect(jsonPath("$.data.items[0].result_count").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.items[0].representative_result").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.items[0].query_text").value("필터만 남은 질의"))
                .andExpect(jsonPath("$.data.items[0].created_at").exists())
                .andExpect(jsonPath("$.data.items[0].explicit_filters.broadcast_date.to")
                        .value("2026-09-15"));
    }

    @Test
    @Transactional
    @DisplayName("조회 대상이 없으면 404 가 아니라 200 과 빈 목록이다")
    void emptyListIsOk() throws Exception {
        mockMvc.perform(get("/api/v1/search/history").with(user(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.total_elements").value(0))
                .andExpect(jsonPath("$.data.total_pages").value(0))
                .andExpect(jsonPath("$.data.has_next").value(false));
    }

    @Test
    @Transactional
    @DisplayName("마지막 페이지 이후도 200·빈 items 이며 실제 총계를 유지한다")
    void pastLastPageKeepsTotals() throws Exception {
        seed();

        mockMvc.perform(get("/api/v1/search/history?page=5&size=10").with(user(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.page").value(5))
                .andExpect(jsonPath("$.data.total_elements").value(3))
                .andExpect(jsonPath("$.data.total_pages").value(1))
                .andExpect(jsonPath("$.data.has_next").value(false));
    }

    @Test
    @Transactional
    @DisplayName("page·size 가 범위 밖이거나 정수가 아니면 400 으로 거부한다")
    void rejectsBadPaging() throws Exception {
        mockMvc.perform(get("/api/v1/search/history?size=101").with(user(OWNER)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/search/history?size=0").with(user(OWNER))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/search/history?page=-1").with(user(OWNER))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/search/history?page=abc").with(user(OWNER)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Transactional
    @DisplayName("빈 page·size 는 기본값이 아니라 400 이다 — 생략과 빈 값을 구분한다")
    void rejectsBlankPaging() throws Exception {
        // Spring 의 defaultValue 는 파라미터 생략뿐 아니라 빈 값에도 기본값을 적용한다.
        mockMvc.perform(get("/api/v1/search/history?page=&size=").with(user(OWNER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMM_400"));
        mockMvc.perform(get("/api/v1/search/history?page=").with(user(OWNER))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/search/history?size=").with(user(OWNER))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/search/history?page=%20").with(user(OWNER)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Transactional
    @DisplayName("파라미터를 생략하면 기본값 0·10 을 적용한다")
    void appliesDefaultsWhenOmitted() throws Exception {
        mockMvc.perform(get("/api/v1/search/history").with(user(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(10));
    }

    @Test
    @DisplayName("세션이 없으면 401 이다")
    void requiresSession() throws Exception {
        mockMvc.perform(get("/api/v1/search/history")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/search/history/9701")).andExpect(status().isUnauthorized());
    }

    @Test
    @Transactional
    @DisplayName("상세의 search_snapshot 은 POST /search 성공 data 와 같은 object 다")
    void detailReturnsSnapshotEqualToSearchResponse() throws Exception {
        seed();

        mockMvc.perform(get("/api/v1/search/history/9701").with(user(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.search_execution_id").value("9701"))
                .andExpect(jsonPath("$.data.snapshot_status").value("available"))
                .andExpect(jsonPath("$.data.result_count").value(1))
                // 상위와 일치하며 envelope 을 중첩하지 않는다.
                .andExpect(
                        jsonPath("$.data.search_snapshot.search_execution_id").value("9701"))
                .andExpect(jsonPath("$.data.search_snapshot.status").value("succeeded"))
                .andExpect(jsonPath("$.data.search_snapshot.isSuccess").doesNotExist())
                .andExpect(jsonPath("$.data.search_snapshot.degraded_reasons").isEmpty())
                .andExpect(jsonPath("$.data.search_snapshot.query_resolution_status")
                        .value("resolved"))
                .andExpect(jsonPath("$.data.search_snapshot.has_applied_review_rule")
                        .value(true))
                // verdicts 중 exclusion_reason 이 있는 1건만 센다.
                .andExpect(jsonPath("$.data.search_snapshot.guard_summary.excluded_result_count")
                        .value(1))
                .andExpect(jsonPath("$.data.search_snapshot.guard_summary.reasons[0]")
                        .value("explicit_date_conflict"))
                .andExpect(
                        jsonPath("$.data.search_snapshot.shortage_reasons[0]").value("candidate_pool_exhausted"))
                .andExpect(jsonPath("$.data.search_snapshot.results.length()").value(1))
                .andExpect(jsonPath("$.data.search_snapshot.results[0].search_result_id")
                        .value("9801"))
                .andExpect(jsonPath("$.data.search_snapshot.results[0].rank").value(1))
                .andExpect(
                        jsonPath("$.data.search_snapshot.results[0].scene_type").value("역사 인파"))
                .andExpect(jsonPath("$.data.search_snapshot.results[0].filmed_date.verification_status")
                        .value("unknown"))
                .andExpect(jsonPath("$.data.search_snapshot.results[0].matched_keywords[0].keyword")
                        .value("서울역"))
                .andExpect(jsonPath("$.data.search_snapshot.results[0].matched_keywords[0].origin")
                        .value("user"))
                .andExpect(jsonPath("$.data.search_snapshot.results[0].match_evidence[0].field")
                        .value("ocr"));
    }

    @Test
    @Transactional
    @DisplayName("정상 완료된 0건 실행은 available·results 빈 배열이며 404 가 아니다")
    void detailOfZeroResultExecutionIsAvailable() throws Exception {
        seed();

        mockMvc.perform(get("/api/v1/search/history/9703").with(user(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.snapshot_status").value("available"))
                .andExpect(jsonPath("$.data.result_count").value(0))
                .andExpect(jsonPath("$.data.representative_result").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.search_snapshot.results").isEmpty());
    }

    @Test
    @Transactional
    @DisplayName("결과 저장이 불완전한 실행의 상세는 search_snapshot 이 null 이고 나머지는 유지된다")
    void detailOfIncompleteRecordHasNullSnapshot() throws Exception {
        seed();

        mockMvc.perform(get("/api/v1/search/history/9702").with(user(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.snapshot_status").value("unavailable"))
                .andExpect(jsonPath("$.data.search_snapshot").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.result_count").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.representative_result").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.query_text").value("필터만 남은 질의"))
                .andExpect(jsonPath("$.data.status").value("succeeded"));
    }

    @Test
    @Transactional
    @DisplayName("타인 소유·미존재·replay 실행은 모두 같은 404 SRCH_404_001 이다")
    void detailHidesEverythingOutOfScopeWithSame404() throws Exception {
        seed();

        for (String path : new String[] {"9701", "9704", "88888"}) {
            mockMvc.perform(get("/api/v1/search/history/" + path).with(user(OTHER)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("SRCH_404_001"));
        }
        // 본인이어도 replay 실행은 이 화면 대상이 아니다.
        mockMvc.perform(get("/api/v1/search/history/9704").with(user(OWNER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SRCH_404_001"));
    }

    @Test
    @Transactional
    @DisplayName("경로 ID 가 0 이하면 400 으로 거부한다")
    void rejectsNonPositivePathId() throws Exception {
        mockMvc.perform(get("/api/v1/search/history/0").with(user(OWNER))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/search/history/-1").with(user(OWNER))).andExpect(status().isBadRequest());
    }

    @Test
    @Transactional
    @DisplayName("지운 기록은 목록·총계·상세에서 빠지고 나머지는 그대로다 (S15P21A501-276)")
    void hidesDeletedRecordFromOwnerViews() throws Exception {
        seed();

        mockMvc.perform(delete("/api/v1/search/history/9701").with(user(OWNER)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true));

        mockMvc.perform(get("/api/v1/search/history?page=0&size=10").with(user(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_elements").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[*].search_execution_id", Matchers.not(Matchers.hasItem("9701"))));

        mockMvc.perform(get("/api/v1/search/history/9701").with(user(OWNER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SRCH_404_001"));

        // 지우지 않은 기록은 영향이 없다.
        mockMvc.perform(get("/api/v1/search/history/9702").with(user(OWNER))).andExpect(status().isOk());
    }

    @Test
    @Transactional
    @DisplayName("행을 지우지 않으므로 감사 조회는 지운 뒤에도 당시 기록을 낸다 (S15P21A501-276)")
    void keepsRowForAuditAfterDelete() throws Exception {
        seed();

        mockMvc.perform(delete("/api/v1/search/history/9701").with(user(OWNER)).with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/search/executions/9701").with(user(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true));

        // 결과 스냅샷도 남아 있어야 문의 상세가 당시 결과를 복원할 수 있다.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM npick.search_result WHERE search_execution_id = 9701", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @Transactional
    @DisplayName("같은 기록을 두 번 지워도 성공하고 지운 시각은 유지된다 (S15P21A501-276)")
    void deleteIsIdempotent() throws Exception {
        seed();

        // 이미 지운 상태를 과거 시각으로 만들어 둔다. 이 테스트는 한 트랜잭션에서 도는데 Postgres 의
        // now() 는 transaction_timestamp() 라 트랜잭션 안에서 고정이다. 갓 지운 행으로 검사하면
        // COALESCE 가 빠져도 두 값이 같아 단언이 통과한다 — 트랜잭션 시각과 다른 값이어야 회귀를 잡는다.
        exec("UPDATE npick.search_execution SET deleted_at = now() - interval '1 day',"
                + " updated_at = now() - interval '1 day' WHERE search_execution_id = 9701");
        String deletedBefore = deletedAt(9701);
        String updatedBefore = updatedAt(9701);

        mockMvc.perform(delete("/api/v1/search/history/9701").with(user(OWNER)).with(csrf()))
                .andExpect(status().isOk());

        // 지운 시각이 밀리면 보존기간 판단의 기준이 흔들린다.
        assertThat(deletedAt(9701)).isEqualTo(deletedBefore);
        // 바뀐 것이 없는 재시도가 감사 조회에 나가는 updated_at 을 흔들지 않는다.
        assertThat(updatedAt(9701)).isEqualTo(updatedBefore);
    }

    @Test
    @Transactional
    @DisplayName("처음 지울 때는 지운 시각과 갱신 시각을 남긴다 (S15P21A501-276)")
    void firstDeleteStampsTimestamps() throws Exception {
        seed();
        exec("UPDATE npick.search_execution SET updated_at = now() - interval '1 day'"
                + " WHERE search_execution_id = 9701");
        String updatedBefore = updatedAt(9701);

        mockMvc.perform(delete("/api/v1/search/history/9701").with(user(OWNER)).with(csrf()))
                .andExpect(status().isOk());

        assertThat(deletedAt(9701)).isNotNull();
        assertThat(updatedAt(9701)).isNotEqualTo(updatedBefore);
    }

    @Test
    @Transactional
    @DisplayName("남의 기록·미존재·대상 밖은 같은 404 로 거부하고 실제로 지우지 않는다 (S15P21A501-276)")
    void rejectsRecordsOutsideOwnScope() throws Exception {
        seed();

        // 9701 은 OWNER 소유, 9704 는 replay, 88888 은 없는 id.
        for (String path : new String[] {"9701", "9704", "88888"}) {
            mockMvc.perform(delete("/api/v1/search/history/" + path)
                            .with(user(OTHER))
                            .with(csrf()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("SRCH_404_001"));
        }
        mockMvc.perform(delete("/api/v1/search/history/9704").with(user(OWNER)).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SRCH_404_001"));

        assertThat(deletedAt(9701)).isNull();
        assertThat(deletedAt(9704)).isNull();
        mockMvc.perform(get("/api/v1/search/history/9701").with(user(OWNER))).andExpect(status().isOk());
    }

    @Test
    @Transactional
    @DisplayName("삭제도 경로 ID 가 0 이하면 400 으로 거부한다 (S15P21A501-276)")
    void rejectsNonPositivePathIdOnDelete() throws Exception {
        mockMvc.perform(delete("/api/v1/search/history/0").with(user(OWNER)).with(csrf()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/v1/search/history/-1").with(user(OWNER)).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    private String deletedAt(long executionId) {
        return column("deleted_at", executionId);
    }

    private String updatedAt(long executionId) {
        return column("updated_at", executionId);
    }

    private String column(String name, long executionId) {
        return jdbc.queryForObject(
                "SELECT CAST(" + name + " AS text) FROM npick.search_execution WHERE search_execution_id = "
                        + executionId,
                String.class);
    }

    private void seed() {
        exec("INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at)"
                + " VALUES (9001, 'editor01', 'hash', '편집기자9001', 'editor', now(), now())");
        exec("INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at)"
                + " VALUES (9003, 'editor03', 'hash', '편집기자9003', 'editor', now(), now())");
        exec("INSERT INTO npick.clip (clip_id, source_type, storage_key, content_hash, transcript_source,"
                + " registered_by_id, created_at, updated_at)"
                + " VALUES (9101, 'broadcast', 'clips/9101/o', repeat('a', 64), 'none', 9001, now(), now())");
        exec("INSERT INTO npick.pipeline_run (pipeline_run_id, clip_id, processing_no, pipeline_version, status,"
                + " stage_states_json, created_at, updated_at)"
                + " VALUES (9201, 9101, 3, 'v1', 'succeeded', '{}'::jsonb, now(), now())");
        exec("INSERT INTO npick.scene (scene_id, clip_id, pipeline_run_id, start_time_ms, end_time_ms, shot_type,"
                + " created_at, updated_at) VALUES (9301, 9101, 9201, 42000, 49000, 'b_roll', now(), now())");

        // 9701 — 결과 1건, 저장 온전. applied_rules_json 에 적용 기록이 있다.
        execution(
                9701,
                9001,
                "서울역 귀성 인파",
                "succeeded",
                "original",
                null,
                "2026-09-15T03:00:00Z",
                FILTERED,
                "[{\"rule_id\": 1, \"status\": \"applied\", \"applied_order\": 1}]");
        exec("INSERT INTO npick.search_result (search_result_id, search_execution_id, scene_id, result_rank,"
                + " explain_json) VALUES (9801, 9701, 9301, 1, '{" + DISPLAY + ", " + MATCH + "}'::jsonb)");

        // 9702 — filtered_json 없음(결과 확정 전). 필터는 읽히므로 유지한다.
        execution(9702, 9001, "필터만 남은 질의", "succeeded", "original", null, "2026-09-15T04:00:00Z", null, "[]");

        // 9703 — 정상 완료 0건. 불완전과 구분되어야 한다.
        execution(
                9703,
                9001,
                "결과 없는 질의",
                "succeeded",
                "original",
                null,
                "2026-09-14T03:00:00Z",
                "{\"returned_count\": 0, \"shortage_reasons\": [\"candidate_pool_exhausted\"],"
                        + " \"guard\": {\"verdicts\": []}}",
                "[]");

        // 9704 — replay. 이 화면 대상이 아니다.
        exec("INSERT INTO npick.feedback (feedback_id, search_result_id, created_by_id, status,"
                + " created_at, updated_at) VALUES (9901, 9801, 9001, 'OPEN', now(), now())");
        execution(9704, 9001, "재검색", "succeeded", "replay", 9901L, "2026-09-15T05:00:00Z", FILTERED, "[]");
    }

    private void execution(
            long id,
            long ownerId,
            String queryText,
            String status,
            String executionType,
            Long replayOfFeedbackId,
            String createdAt,
            String filteredJson,
            String appliedRulesJson) {
        exec("INSERT INTO npick.search_execution (search_execution_id, searched_by_id, query_text,"
                + " normalized_query, explicit_filters_json, normalized_filters_json, query_fingerprint,"
                + " normalization_version, execution_type, replay_of_feedback_id, status, degraded_reasons_json,"
                + " applied_excludes_json, search_config_json, config_version, parse_source, applied_rules_json,"
                + " filtered_json, created_at, updated_at) VALUES ("
                + id + ", " + ownerId + ", '" + queryText + "', '" + queryText + "',"
                + " '{\"broadcast_date\": {\"from\": \"2026-09-01\", \"to\": \"2026-09-15\"}}'::jsonb,"
                + " '{}'::jsonb, 'fp-" + id + "', 'v1', '" + executionType + "', "
                + (replayOfFeedbackId == null ? "NULL" : replayOfFeedbackId) + ", '" + status + "',"
                + " '[]'::jsonb, '[]'::jsonb, '{}'::jsonb, 'cfg-v1', 'resolver_rule', '"
                + appliedRulesJson + "'::jsonb, "
                + (filteredJson == null ? "NULL" : "'" + filteredJson + "'::jsonb")
                + ", '" + createdAt + "'::timestamptz, now())");
    }

    private void exec(String sql) {
        jdbc.execute(sql);
    }
}
