package com.npick.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDate;

import com.npick.search.application.query.search.ExecuteSearchQuery.DateFilters;
import com.npick.search.application.query.search.PendingCandidates;
import com.npick.search.application.query.search.PendingCandidatesPort;
import com.npick.search.application.query.search.VerificationInput;
import com.npick.search.application.query.search.VerificationInputPort;
import com.npick.support.NpickPostgres;
import com.npick.support.TestGraph;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class VerificationInputLoadDbTest {

    private static final long MEMBER_ID = 8302001L, CLIP_ID = 8302010L, RUN_ID = 8302020L,
            SCENE_ID = 8302030L, EXEC_ID = 8302040L, RESULT_ID = 8302050L, FEEDBACK_ID = 8302060L;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) { NpickPostgres.datasource(registry); }

    @Autowired private VerificationInputPort inputPort;
    @Autowired private PendingCandidatesPort candidatesPort;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        TestGraph.insertReportedScene(jdbc, MEMBER_ID, CLIP_ID, RUN_ID, SCENE_ID, EXEC_ID, RESULT_ID, FEEDBACK_ID);
    }

    @Test
    @DisplayName("원 실행의 검색어와 명시 필터를 자동으로 가져온다")
    void loadsOriginalQuery() {
        VerificationInput input = inputPort.load(FEEDBACK_ID);
        assertThat(input.rawQuery()).isEqualTo("원본질의");
        assertThat(input.dateFilters()).isNotNull();
    }

    @Test
    @DisplayName("채워진 explicit_filters_json(§5b 실제 형태)의 날짜 필터를 정확히 파싱한다")
    void loadsPopulatedDateFilters() {
        jdbc.update("UPDATE npick.search_execution SET explicit_filters_json = ?::jsonb WHERE search_execution_id = ?",
                "{\"broadcast_date\":{\"from\":\"2026-01-01\",\"to\":\"2026-03-31\"},"
                        + "\"filmed_date\":{\"from\":\"2025-12-01\",\"to\":null}}",
                EXEC_ID);

        VerificationInput input = inputPort.load(FEEDBACK_ID);

        assertThat(input.dateFilters()).isEqualTo(new DateFilters(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31), LocalDate.of(2025, 12, 1), null));
    }

    @Test
    @DisplayName("대기 태그 후보(confirmed=false)를 신고 범위로 로드한다")
    void loadsPendingTagCandidate() {
        long taggingId = TestGraph.insertReviewerTagCandidate(jdbc, SCENE_ID, CLIP_ID, FEEDBACK_ID, 8302070L);
        PendingCandidates pending = candidatesPort.load(FEEDBACK_ID);
        assertThat(pending.tagEvidenceIds()).contains(8302070L);
    }

    @Test
    @DisplayName("대기 규칙 후보의 R2/R1(replaces_rule_id)을 로드한다")
    void loadsPendingRuleCandidate() {
        TestGraph.insertActivePatchRule(jdbc, FEEDBACK_ID, 8302080L);          // R1 active
        TestGraph.insertPendingPatchRuleReplacing(jdbc, FEEDBACK_ID, 8302081L, 8302080L); // R2 -> R1
        PendingCandidates pending = candidatesPort.load(FEEDBACK_ID);
        assertThat(pending.approvedRuleId()).isEqualTo(8302081L);
        assertThat(pending.replacedRuleId()).isEqualTo(8302080L);
    }
}
