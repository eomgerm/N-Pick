package com.npick.search;

import org.junit.jupiter.api.AfterEach;
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

    /**
     * loadsPendingRuleCandidate() 가 {@code TestGraph.insertActivePatchRule} 로 jdbc 에 직접 커밋한 활성 규칙(8302080)은
     * 이 테스트가 {@code verify()} 를 부르지 않아 롤백 대상이 아니다. 공유 DB 에 {@code active=true} 로 남으면
     * {@code ParseRuleRepositoryAdapter.findActivePatchParseRules()} 가 전역(질의 무관) 조회라 다음에 실행되는 다른 검증
     * DbTest 의 실 {@code interpret()} 까지 오염시킨다.
     */
    @AfterEach
    void deactivateLeakedActiveRule() {
        jdbc.update("UPDATE npick.search_rule SET active = false WHERE search_rule_id = ?", 8302080L);
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
        assertThat(pending.rules()).containsExactly(
                new PendingCandidates.RuleCandidate(8302081L, 8302080L));
    }
}
