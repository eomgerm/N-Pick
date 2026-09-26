package com.npick.search.infrastructure.persistence.query;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.npick.search.application.query.candidate.SceneCandidateResult;
import com.npick.search.infrastructure.config.SceneCandidateProperties;
import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 실제 ParadeDB 후보 집합과 원 질의 고유 토큰 커버리지의 점수·순위를 함께 검증한다. */
class WordSceneCandidateCoverageTest {
    private static String url;
    private Connection connection;
    private SingleConnectionDataSource dataSource;

    @BeforeAll
    static void migrateOwnDatabase() {
        url = NpickPostgres.freshDatabase("npick_scene_coverage_test");
        NpickPostgres.migrate(url);
    }

    @BeforeEach
    void loadFixture() throws Exception {
        connection = DriverManager.getConnection(url, NpickPostgres.username(), NpickPostgres.password());
        connection.setAutoCommit(false);
        execute("SET LOCAL search_path = npick, public");
        execute(resource("search/scene-candidate-fixture.sql"));
        execute(resource("search/expanded-phrase-fixture.sql"));
        dataSource = new SingleConnectionDataSource(connection, true);
    }

    @AfterEach
    void rollbackFixture() throws Exception {
        if (dataSource != null) dataSource.destroy();
        if (connection != null && !connection.isClosed()) {
            try {
                connection.rollback();
            } finally {
                connection.close();
            }
        }
    }

    @Test
    void positiveWeightRewardsMoreDistinctOriginalTokensWithoutRemovingCandidates() throws Exception {
        insertScenes("(90,11,22,0,1000,'정치인 옷 입 빨갛 색 " + "배경 ".repeat(5)
                + "',NULL,'b_roll',now(),now()),"
                + "(91,11,22,0,1000,'정치인 옷 옷 옷',NULL,'b_roll',now(),now())");
        var tokens = List.of("정치인", "옷", "입", "빨갛", "색");
        var baseline = adapter(0).findByWords(tokens, List.of());
        var boosted = adapter(0.5).findByWords(tokens, List.of());

        System.out.println("coverage ranking baseline=" + baseline + "; boosted=" + boosted);
        assertThat(sceneIds(boosted)).containsExactlyInAnyOrderElementsOf(sceneIds(baseline));
        assertThat(sceneIds(baseline)).containsExactly(91L, 90L);
        assertThat(only(baseline, 90).rawScore() / only(baseline, 91).rawScore())
                .isBetween(0.7, 1.0);
        assertThat(sceneIds(boosted).indexOf(90L)).isLessThan(sceneIds(baseline).indexOf(90L));
        assertThat(sceneIds(boosted)).containsExactly(90L, 91L);
        assertThat(only(boosted, 90).matchedQueryTokenCount()).isEqualTo(5);
        assertThat(only(boosted, 91).matchedQueryTokenCount()).isEqualTo(2);
        assertThat(only(boosted, 91).coverageRatio()).isEqualTo(0.4);
        assertThat(only(boosted, 91).coverageBonus()).isEqualTo(0.2);
        assertThat(only(boosted, 90).rawScore()).isEqualTo(only(baseline, 90).rawScore());
    }

    @Test
    void repeatedTokenAcrossFieldsAndOcrCountsOnce() throws Exception {
        execute("INSERT INTO keyframe VALUES (43,30,500,'test/f43')");
        execute("INSERT INTO ocr_observation VALUES (53,43,'화재 단독','화재 단독 단독',0.9,'{}')");
        var candidate = only(adapter(0.5).findByWords(List.of("화재", "단독"), List.of()), 30);
        assertThat(candidate.matchedQueryTokenCount()).isEqualTo(2);
        assertThat(candidate.queryTokenCount()).isEqualTo(2);
        assertThat(candidate.coverageRatio()).isEqualTo(1);
        assertThat(candidate.coverageBonus()).isEqualTo(0.5);
    }

    @Test
    void duplicateInputTokenDoesNotChangeDenominatorOrRanking() {
        var once = adapter(0.5).findByWords(List.of("화재", "단독"), List.of());
        var duplicate = adapter(0.5).findByWords(List.of("화재", "화재", "단독"), List.of());
        assertThat(duplicate)
                .allSatisfy(candidate -> assertThat(candidate.queryTokenCount()).isEqualTo(2));
        assertThat(duplicate).containsExactlyElementsOf(once);
    }

    @Test
    void singleOriginalTokenAndExpandedOnlyHitsKeepLegacyOrder() throws Exception {
        insertScenes("(90,11,22,0,1000,'짜장면',NULL,'b_roll',now(),now())");
        var off = adapter(0).findByWords(List.of("짜장면"), List.of(List.of("중국", "음식")));
        var on = adapter(0.5).findByWords(List.of("짜장면"), List.of(List.of("중국", "음식")));
        assertThat(sceneIds(on)).containsExactlyElementsOf(sceneIds(off)).containsExactlyInAnyOrder(90L, 61L);
        assertThat(on).allSatisfy(candidate -> {
            assertThat(candidate.queryTokenCount()).isEqualTo(1);
            assertThat(candidate.coverageBonus()).isZero();
        });
        assertThat(only(on, 90).matchedQueryTokenCount()).isEqualTo(1);
        assertThat(only(on, 90).coverageRatio()).isEqualTo(1);
        assertThat(only(on, 61).matchedQueryTokenCount()).isZero();
        assertThat(only(on, 61).coverageRatio()).isZero();
    }

    @Test
    void finiteExtremeWeightDoesNotOverflowBeforeDividingByQueryTokenCount() {
        var candidates = adapter(Double.MAX_VALUE).findByWords(List.of("화재", "단독"), List.of());
        var complete = only(candidates, 30);
        assertThat(complete.coverageRatio()).isEqualTo(1);
        assertThat(complete.coverageBonus()).isEqualTo(Double.MAX_VALUE);
        assertThat(complete.score()).isFinite();
        var partial = only(candidates, 31);
        assertThat(partial.coverageRatio()).isEqualTo(0.5);
        assertThat(partial.coverageBonus()).isEqualTo(Double.MAX_VALUE / 2);
        assertThat(partial.score()).isFinite();
    }

    @Test
    void minimumSubnormalWeightRoundsHalfCoverageToZeroAndThreeQuartersUp() {
        var candidates = adapter(Double.MIN_VALUE).findByWords(List.of("화재", "단독"), List.of());
        var complete = only(candidates, 30);
        assertThat(complete.coverageBonus()).isEqualTo(Double.MIN_VALUE);
        var partial = only(candidates, 31);
        assertThat(partial.coverageRatio()).isEqualTo(0.5);
        assertThat(partial.coverageBonus()).isZero();
        assertThat(partial.score()).isFinite();
        var threeQuarters =
                only(adapter(Double.MIN_VALUE).findByWords(List.of("공장", "화재", "단독", "없는토큰"), List.of()), 30);
        assertThat(threeQuarters.coverageRatio()).isEqualTo(0.75);
        assertThat(threeQuarters.coverageBonus()).isEqualTo(Double.MIN_VALUE);
    }

    @Test
    void expandedPhrasesNeverEnterOriginalTokenCoverage() {
        var candidates = adapter(0.5).findByWords(List.of("화재", "단독"), List.of(List.of("중국", "음식")));
        assertThat(candidates)
                .allSatisfy(candidate -> assertThat(candidate.queryTokenCount()).isEqualTo(2));
        var expandedOnly = only(candidates, 61);
        assertThat(expandedOnly.rawScore()).isPositive();
        assertThat(expandedOnly.matchedQueryTokenCount()).isZero();
        assertThat(expandedOnly.coverageRatio()).isZero();
        assertThat(expandedOnly.coverageBonus()).isZero();
        assertThat(only(candidates, 30).matchedQueryTokenCount()).isEqualTo(2);
    }

    @Test
    void disabledOcrFieldCannotContributeCoverage() {
        var candidate = only(adapter(1, 1, 0, 0.5, 1000).findByWords(List.of("화재", "단독"), List.of()), 30);
        assertThat(candidate.ocrScore()).isZero();
        assertThat(candidate.matchedQueryTokenCount()).isEqualTo(1);
        assertThat(candidate.coverageRatio()).isEqualTo(0.5);
    }

    @Test
    void disabledCaptionFieldCannotContributeCoverage() {
        var candidate = only(adapter(0, 1, 1, 0.5, 1000).findByWords(List.of("공장", "단독"), List.of()), 30);
        assertThat(candidate.textScore()).isZero();
        assertThat(candidate.matchedQueryTokenCount()).isEqualTo(1);
        assertThat(candidate.coverageRatio()).isEqualTo(0.5);
    }

    @Test
    void disabledTranscriptFieldCannotContributeCoverage() {
        var candidate = only(adapter(1, 0, 1, 0.5, 1000).findByWords(List.of("기자", "원인"), List.of()), 31);
        assertThat(candidate.matchedQueryTokenCount()).isEqualTo(1);
        assertThat(candidate.coverageRatio()).isEqualTo(0.5);
    }

    @Test
    void bothTextFieldsDisabledLeaveOnlyOcrCoverage() {
        var candidates = adapter(0, 0, 1, 0.5, 1000).findByWords(List.of("화재", "단독"), List.of());
        assertThat(sceneIds(candidates)).containsExactlyInAnyOrder(30L, 34L);
        assertThat(candidates).allSatisfy(candidate -> {
            assertThat(candidate.textScore()).isZero();
            assertThat(candidate.matchedQueryTokenCount()).isEqualTo(1);
            assertThat(candidate.coverageBonus()).isEqualTo(0.25);
        });
    }

    @Test
    void zeroWeightNormalizesRawScoresWithoutChangingRawOrder() {
        var candidates = adapter(0).findByWords(List.of("화재", "단독"), List.of());
        assertThat(candidates.getFirst().score()).isEqualTo(1);
        assertThat(candidates)
                .extracting(SceneCandidateResult::rawScore)
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
        double maximum = candidates.getFirst().rawScore();
        assertThat(candidates).allSatisfy(candidate -> {
            assertThat(candidate.queryTokenCount()).isEqualTo(2);
            assertThat(candidate.rawScore()).isCloseTo(candidate.textScore() + candidate.ocrScore(), within(0.000001));
            assertThat(candidate.score()).isCloseTo(candidate.rawScore() / maximum, within(0.000001));
            assertThat(candidate.coverageBonus()).isZero();
        });
    }

    @Test
    void scoreAddsCoverageAfterNormalizationAndPoolLimitFollowsRanking() {
        var tokens = List.of("화재", "단독");
        var all = adapter(0.5).findByWords(tokens, List.of());
        double maximum =
                all.stream().mapToDouble(SceneCandidateResult::rawScore).max().orElseThrow();
        assertThat(all).allSatisfy(candidate -> {
            assertThat(candidate.queryTokenCount()).isEqualTo(2);
            assertThat(candidate.score())
                    .isCloseTo(candidate.rawScore() / maximum + candidate.coverageBonus(), within(0.000001));
        });
        assertThat(adapter(1, 1, 1, 0.5, 1).findByWords(tokens, List.of())).containsExactly(all.getFirst());
    }

    @Test
    void tiedScoresUseAscendingSceneIdAndExcludeInactiveAndDeletedScenes() throws Exception {
        insertScenes("(91,11,22,0,1000,'동률 하나',NULL,'b_roll',now(),now()),"
                + "(90,11,22,0,1000,'동률 하나',NULL,'b_roll',now(),now()),"
                + "(92,10,20,0,1000,'동률 하나',NULL,'b_roll',now(),now()),"
                + "(93,12,23,0,1000,'동률 하나',NULL,'b_roll',now(),now())");
        var candidates = adapter(0.5).findByWords(List.of("동률", "하나"), List.of());
        assertThat(sceneIds(candidates)).containsExactly(90L, 91L);
        assertThat(candidates).allSatisfy(candidate -> {
            assertThat(candidate.score()).isEqualTo(1.5);
            assertThat(candidate.matchedQueryTokenCount()).isEqualTo(2);
        });
    }

    private WordSceneCandidateAdapter adapter(double coverage) {
        return adapter(1, 1, 1, coverage, 1000);
    }

    private WordSceneCandidateAdapter adapter(
            double caption, double transcript, double ocr, double coverage, int pool) {
        return new WordSceneCandidateAdapter(
                new NamedParameterJdbcTemplate(dataSource),
                new SceneCandidateProperties(
                        "test-coverage", caption, transcript, ocr, 0.3, coverage, pool, List.of()));
    }

    private static SceneCandidateResult only(List<SceneCandidateResult> candidates, long sceneId) {
        return candidates.stream()
                .filter(candidate -> candidate.sceneId() == sceneId)
                .findFirst()
                .orElseThrow();
    }

    private static List<Long> sceneIds(List<SceneCandidateResult> candidates) {
        return candidates.stream().map(SceneCandidateResult::sceneId).toList();
    }

    private void insertScenes(String values) throws Exception {
        execute("INSERT INTO scene (scene_id,clip_id,pipeline_run_id,start_time_ms,end_time_ms,"
                + "caption_tokens,transcript_tokens,shot_type,created_at,updated_at) VALUES " + values);
    }

    private void execute(String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String resource(String path) throws Exception {
        try (var stream = WordSceneCandidateCoverageTest.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) throw new IllegalArgumentException("Missing resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
